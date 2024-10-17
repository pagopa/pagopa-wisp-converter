package it.gov.pagopa.wispconverter.service;

import com.azure.cosmos.models.PartitionKey;
import com.google.gson.Gson;
import gov.telematici.pagamenti.ws.nodoperpa.ppthead.IntestazionePPT;
import gov.telematici.pagamenti.ws.pafornode.PaSendRTV2Request;
import it.gov.pagopa.gen.wispconverter.client.cache.model.ConnectionDto;
import it.gov.pagopa.gen.wispconverter.client.cache.model.StationDto;
import it.gov.pagopa.wispconverter.controller.ReceiptController;
import it.gov.pagopa.wispconverter.controller.model.*;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.RPTRequestRepository;
import it.gov.pagopa.wispconverter.repository.RTRetryRepository;
import it.gov.pagopa.wispconverter.repository.model.*;
import it.gov.pagopa.wispconverter.repository.model.enumz.IdempotencyStatusEnum;
import it.gov.pagopa.wispconverter.repository.model.enumz.InternalStepStatus;
import it.gov.pagopa.wispconverter.repository.model.enumz.ReceiptStatusEnum;
import it.gov.pagopa.wispconverter.repository.model.enumz.ReceiptTypeEnum;
import it.gov.pagopa.wispconverter.secondary.IdempotencyKeyRepositorySecondary;
import it.gov.pagopa.wispconverter.secondary.RTRepositorySecondary;
import it.gov.pagopa.wispconverter.secondary.ReEventRepositorySecondary;
import it.gov.pagopa.wispconverter.service.model.re.ReEventDto;
import it.gov.pagopa.wispconverter.service.model.session.CommonFieldsDTO;
import it.gov.pagopa.wispconverter.service.model.session.RPTContentDTO;
import it.gov.pagopa.wispconverter.service.model.session.ReceiptContentDTO;
import it.gov.pagopa.wispconverter.service.model.session.SessionDataDTO;
import it.gov.pagopa.wispconverter.util.*;
import jakarta.xml.soap.SOAPMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
@RequiredArgsConstructor
public class RecoveryService {

    @Value("${wisp-converter.station-in-forwarder.partial-path}")
    private String stationInForwarderPartialPath;

    @Value("${wisp-converter.forwarder.api-key}")
    private String forwarderSubscriptionKey;
    private static final String RPT_ACCETTATA_NODO = "RPT_ACCETTATA_NODO";

    private static final String STATUS_RT_SEND_SUCCESS = "RT_SEND_SUCCESS";

    private static final String RECOVERY_VALID_START_DATE = "2024-09-03";

    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE_FORMAT_DAY = "yyyy-MM-dd";
    private static final List<String> blockedReceiptStatus = List.of(
            ReceiptStatusEnum.NOT_SENT.name(),
            ReceiptStatusEnum.REDIRECT.name(),
            ReceiptStatusEnum.SCHEDULED.name());

    private final RTRepositorySecondary rtRepository;

    private final ReEventRepositorySecondary reEventRepository;

    private final IdempotencyKeyRepositorySecondary idempotencyKeyRepositorySecondary;

    private final ReceiptService receiptService;

    private final RPTRequestRepository rptRequestRepository;

    private final RTRetryRepository rtRetryRepository;

    private final ReService reService;

    private final RPTExtractorService rptExtractorService;

    private final ConfigCacheService configCacheService;

    private final ServiceBusService serviceBusService;

    private final JaxbElementUtil jaxbElementUtil;

    @Value("${wisp-converter.cached-requestid-mapping.ttl.minutes:1440}")
    public Long requestIDMappingTTL;
    @Value("${wisp-converter.recovery.receipt-generation.wait-time.minutes:60}")
    public Long receiptGenerationWaitTime;
    @Value("${wisp-converter.apim.path}")
    private String apimPath;

    // Recover by IUV
    public RecoveryReceiptResponse recoverReceiptKOByIUV(String creditorInstitution, String iuv, String dateFrom, String dateTo) {
        // Query database for blocked Receipt in given timestamp with given IUV
        List<RTEntity> rtEntities = rtRepository.findByMidReceiptStatusInAndTimestampBetween(getDateFrom(dateFrom), getDateTo(dateTo), creditorInstitution, iuv);
        // For each entity call send receipt KO
        rtEntities.forEach(rtEntity -> callSendReceiptKO(rtEntity.getDomainId(), rtEntity.getIuv(), rtEntity.getCcp(), rtEntity.getSessionId()));
        return this.extractRecoveryReceiptResponse(rtEntities);
    }

    // Recover by CI (async)
    public RecoveryReceiptResponse recoverReceiptKOByCI(String creditorInstitution, String dateFrom, String dateTo) {
        // Query database for blocked Receipt in given timestamp with given domainId (-> creditorInstitution)
        List<RTEntity> rtEntities = rtRepository.findByMidReceiptStatusInAndTimestampBetween(getDateFrom(dateFrom), getDateTo(dateTo), creditorInstitution);
        // Future
        CompletableFuture<Boolean> executeRecovery = CompletableFuture.supplyAsync(() -> {
            rtEntities.forEach(rtEntity -> callSendReceiptKO(rtEntity.getDomainId(), rtEntity.getIuv(), rtEntity.getCcp(), rtEntity.getSessionId()));
            return true;
        });
        executeRecovery
                .thenAccept(value -> log.debug("Reconciliation for creditor institution [{}] in date range [{}-{}] completed!", creditorInstitution, dateFrom, dateTo))
                .exceptionally(e -> {
                    log.error("Reconciliation for creditor institution [{}] in date range [{}-{}] ended unsuccessfully!", creditorInstitution, dateFrom, dateTo, e);
                    throw new AppException(e, AppErrorCodeMessageEnum.ERROR, e.getMessage());
                });
        // Returns the entities that will be recovered in async
        return extractRecoveryReceiptResponse(rtEntities);
    }

    // Recover by dates (cron)
    public RecoveryReceiptResponse recoverReceiptKOByDate(ZonedDateTime dateFrom, ZonedDateTime dateTo) {
        // Query database for blocked Receipt in given timestamp
        List<RTEntity> rtEntities = rtRepository.findByMidReceiptStatusInAndTimestampBetween(dateFrom.toString(), dateTo.toString());
        // For each entity call send receipt KO
        rtEntities.forEach(rtEntity -> callSendReceiptKO(rtEntity.getDomainId(), rtEntity.getIuv(), rtEntity.getCcp(), rtEntity.getSessionId()));
        return this.extractRecoveryReceiptResponse(rtEntities);
    }

    // missing redirect recovery
    public int recoverMissingRedirect(ZonedDateTime dateFrom, ZonedDateTime dateTo) {
        String dateFromString = dateFrom.format(DateTimeFormatter.ofPattern(DATE_FORMAT_DAY));
        String dateToString = dateTo.format(DateTimeFormatter.ofPattern(DATE_FORMAT_DAY));
        String dateFromTSString = dateFrom.format(DateTimeFormatter.ofPattern(DATE_FORMAT));
        String dateToTSString = dateTo.format(DateTimeFormatter.ofPattern(DATE_FORMAT));
        int sent = 0;

        List<SessionIdEntity> sessionsWithoutRedirect = reEventRepository.findSessionWithoutRedirect(dateFromTSString, dateToTSString);

        for (SessionIdEntity sessionIdEntity : sessionsWithoutRedirect) {
            String sessionId = sessionIdEntity.getSessionId();
            List<ReEventEntity> reEventList = reEventRepository.findBySessionIdAndStatus(dateFromString, dateToString, sessionId, RPT_ACCETTATA_NODO, 1);

            if (!reEventList.isEmpty()) {
                ReEventEntity reEvent = reEventList.get(0);
                String iuv = reEvent.getIuv();
                String ccp = reEvent.getCcp();
                String ci = reEvent.getDomainId();

                List<ReEventEntity> reEventsRT = reEventRepository.findBySessionIdAndStatus(dateFromString, dateToString, sessionId, STATUS_RT_SEND_SUCCESS, 1);
                if (reEventsRT.isEmpty()) {
                    this.callSendReceiptKO(ci, iuv, ccp, sessionId);
                    sent++;
                }
            }
        }

        return sent;
    }

    // call sendRTKoFromSessionId
    public void callSendReceiptKO(String ci, String iuv, String ccp, String sessionId) {
        MDC.put(Constants.MDC_BUSINESS_PROCESS, "recovery-receipt-ko");

        generateRE(Constants.PAA_INVIA_RT, null, InternalStepStatus.RT_START_RECONCILIATION_PROCESS, ci, iuv, ccp, sessionId);

        try {
            log.info("[WISP-Recovery][SEND-RECEIPT-KO] receipt-ko for ci = {}, iuv = {}, ccp = {}, sessionId = {}", ci, iuv, ccp, sessionId);
            this.receiptService.sendRTKoFromSessionId(sessionId, InternalStepStatus.NEGATIVE_RT_TRY_TO_SEND_TO_CREDITOR_INSTITUTION);
        } catch (Exception e) {
            generateRE(Constants.PAA_INVIA_RT, "Failure", InternalStepStatus.RT_END_RECONCILIATION_PROCESS, ci, iuv, ccp, sessionId);
            throw new AppException(e, AppErrorCodeMessageEnum.ERROR, e.getMessage());
        }
        generateRE(Constants.PAA_INVIA_RT, "Success", InternalStepStatus.RT_END_RECONCILIATION_PROCESS, ci, iuv, ccp, sessionId);
        MDC.remove(Constants.MDC_BUSINESS_PROCESS);
    }

    // checks date validity
    private String getDateFrom(String dateFrom) {
        LocalDate lowerLimit = LocalDate.parse(RECOVERY_VALID_START_DATE, DateTimeFormatter.ISO_LOCAL_DATE);
        LocalDate dateFromLocalDate = LocalDate.parse(dateFrom, DateTimeFormatter.ISO_LOCAL_DATE);
        if (dateFromLocalDate.isBefore(lowerLimit)) {
            throw new AppException(AppErrorCodeMessageEnum.ERROR, String.format("The lower bound cannot be lower than [%s]", RECOVERY_VALID_START_DATE));
        }
        return dateFrom;
    }

    // checks date validity and adjusts the value if necessary
    private String getDateTo(String dateTo) {
        LocalDate now = LocalDate.now();
        String dateToRefactored;

        LocalDate dateToLocalDate = LocalDate.parse(dateTo, DateTimeFormatter.ISO_LOCAL_DATE);
        if (dateToLocalDate.isAfter(now)) {
            throw new AppException(AppErrorCodeMessageEnum.ERROR, String.format("The upper bound cannot be higher than [%s]", now.format(DateTimeFormatter.ofPattern(DATE_FORMAT_DAY))));
        }

        if (LocalDate.now().isEqual(dateToLocalDate)) {
            ZonedDateTime nowMinusMinutes = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(receiptGenerationWaitTime);
            dateToRefactored = nowMinusMinutes.format(DateTimeFormatter.ofPattern(DATE_FORMAT));
        } else {
            dateToRefactored = dateTo + " 23:59:59";
        }

        return dateToRefactored;
    }

    private RecoveryReceiptResponse extractRecoveryReceiptResponse(List<RTEntity> rtEntities) {
        List<RecoveryReceiptPaymentResponse> responses = new ArrayList<>();

        for (RTEntity rtEntity : rtEntities) {
            responses.add(
                    RecoveryReceiptPaymentResponse.builder()
                            .iuv(rtEntity.getIuv())
                            .ccp(rtEntity.getCcp())
                            .ci(rtEntity.getDomainId())
                            .build());
        }

        return RecoveryReceiptResponse.builder()
                .payments(responses)
                .build();
    }

    private void generateRE(String primitive, String operationStatus, InternalStepStatus status, String domainId, String iuv, String ccp, String sessionId) {
        generateRE(primitive, operationStatus, status, domainId, iuv, ccp, sessionId, null);
    }

    private void generateRE(String primitive, String operationStatus, InternalStepStatus status, String domainId, String iuv, String ccp, String sessionId, String info) {

        // setting data in MDC for next use
        ReEventDto reEvent = ReUtil.getREBuilder()
                .primitive(primitive)
                .operationStatus(operationStatus)
                .status(status)
                .sessionId(sessionId)
                .domainId(domainId)
                .iuv(iuv)
                .ccp(ccp)
                .noticeNumber(null)
                .info(info)
                .build();
        reService.addRe(reEvent);
    }

    @Transactional
    public RecoveryReceiptReportResponse recoverReceiptOkToBeReSentBySessionIds(RecoveryReceiptBySessionIdRequest request) {
        List<String> receiptsIds = new ArrayList<>();
        try {
            for (String sessionId : request.getSessionIds()) {
                // extract rpt from re
                List<ReEventEntity> reItems = reEventRepository.findRptAccettataNodoBySessionId(sessionId);
                for(ReEventEntity reItem : reItems) {
                    String[] brokerEC = reItem.getStation().split("_");
                    String receiptId = brokerEC[0] + "_" + UUID.randomUUID();
                    IdempotencyKeyEntity idempotencyKey = IdempotencyKeyEntity.builder()
                            .id(String.format("%s_%s_%s", reItem.getSessionId(), reItem.getIuv(), reItem.getDomainId()))
                            .partitionKey(reItem.getPartitionKey())
                            .receiptType(ReceiptTypeEnum.OK)
                            .sessionId(reItem.getSessionId())
                            .status(IdempotencyStatusEnum.FAILED)
                            .build();
                    idempotencyKeyRepositorySecondary.save(idempotencyKey);
                    String payload = "<soapenv:Envelope\n" +
                            "\txmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"\n" +
                            "\txmlns:ns2=\"http://ws.pagamenti.telematici.gov/ppthead\"\n" +
                            "\txmlns:ns3=\"http://ws.pagamenti.telematici.gov/\"><soapenv:Header><ns2:intestazionePPT\n" +
                            "\t\t\txmlns:ns4=\"http://schemas.xmlsoap.org/soap/envelope/\"><identificativoIntermediarioPA></identificativoIntermediarioPA><identificativoStazioneIntermediarioPA></identificativoStazioneIntermediarioPA><identificativoDominio>DOMINIO</identificativoDominio><identificativoUnivocoVersamento>IUV</identificativoUnivocoVersamento><codiceContestoPagamento></codiceContestoPagamento></ns2:intestazionePPT></soapenv:Header><soapenv:Body><ns3:paaInviaRT><tipoFirma/><rt></rt></ns3:paaInviaRT></soapenv:Body></soapenv:Envelope>"
                                    .replace("DOMINIO", reItem.getDomainId())
                                    .replace("IUV", reItem.getIuv());

                    // create a RTRequestEntity to generate the ok receipt
                    RTRequestEntity receipt = RTRequestEntity.builder()
                            .id(receiptId)
                            .partitionKey(reItem.getPartitionKey())
                            .domainId(reItem.getDomainId())
                            .idempotencyKey(idempotencyKey.getId())
                            .iuv(reItem.getIuv())
                            .ccp(reItem.getCcp())
                            .sessionId(sessionId)
                            .payload(AppBase64Util.base64Encode(ZipUtil.zip(payload)))
                            .primitive(reItem.getPrimitive())
                            .receiptType(ReceiptTypeEnum.OK)
                            .station(reItem.getStation())
                            .build();
                    rtRetryRepository.save(receipt);
                    receiptsIds.add(receiptId);
                }
            }


            RecoveryReceiptRequest req = RecoveryReceiptRequest.builder()
                    .receiptIds(receiptsIds)
                    .build();

            return recoverReceiptToBeReSent(req);
        }
        catch (IOException e) {
            throw new AppException(AppErrorCodeMessageEnum.ERROR, "Problem with receipt payload");
        }
    }

    public RecoveryReceiptReportResponse recoverReceiptToBeReSentByPartition(RecoveryReceiptByPartitionRequest request) {

        List<String> receiptsIds = request.getPartitionKeys().stream()
                .map(PartitionKey::new)
                .flatMap(partitionKey -> StreamSupport.stream(rtRetryRepository.findAll(partitionKey).spliterator(), false))
                .map(RTRequestEntity::getId)
                .toList();

        RecoveryReceiptRequest req = RecoveryReceiptRequest.builder()
                .receiptIds(receiptsIds)
                .build();

        return recoverReceiptToBeReSent(req);
    }

    public RecoveryReceiptReportResponse recoverReceiptToBeReSent(RecoveryReceiptRequest request) {

        RecoveryReceiptReportResponse response = RecoveryReceiptReportResponse.builder()
                .receiptStatus(new LinkedList<>())
                .build();

        MDCUtil.setSessionDataInfo("recovery-receipt-ondemand");

        gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory = new gov.telematici.pagamenti.ws.papernodo.ObjectFactory();
        for (String receiptId : request.getReceiptIds()) {

            String sessionId = null;
            try {
                Optional<RTRequestEntity> rtRequestEntityOpt = rtRetryRepository.findById(receiptId);
                if (rtRequestEntityOpt.isEmpty()) {
                    throw new AppException(AppErrorCodeMessageEnum.ERROR, String.format("No valid receipt found with id [%s]", receiptId));
                }

                RTRequestEntity rtRequestEntity = rtRequestEntityOpt.get();
                String idempotencyKey = rtRequestEntity.getIdempotencyKey();
                String[] idempotencyKeyComponents = idempotencyKey.split("_");
                if (idempotencyKeyComponents.length < 2) {
                    throw new AppException(AppErrorCodeMessageEnum.ERROR, String.format("Invalid idempotency key [%s]. It must be composed of sessionId, notice number and domain.", idempotencyKey));
                }

                sessionId = idempotencyKeyComponents[0];
                MDC.put(Constants.MDC_SESSION_ID, sessionId);
                Optional<RPTRequestEntity> rptRequestOpt = rptRequestRepository.findById(sessionId);
                if (rptRequestOpt.isEmpty()) {
                    throw new AppException(AppErrorCodeMessageEnum.ERROR, String.format("No valid RPT request found with id [%s].", sessionId));
                }

                RPTRequestEntity rptRequestEntity = rptRequestOpt.get();
                SessionDataDTO sessionData = rptExtractorService.extractSessionData(rptRequestEntity.getPrimitive(), rptRequestEntity.getPayload());

                String stationId = sessionData.getCommonFields().getStationId();
                StationDto station = configCacheService.getStationByIdFromCache(stationId);

                // regenerate destination networking info
                ConnectionDto stationConnection = station.getConnection();
                URI uri = CommonUtility.constructUrl(
                        stationConnection.getProtocol().getValue(),
                        stationConnection.getIp(),
                        stationConnection.getPort().intValue(),
                        station.getService() != null ? station.getService().getPath() : ""
                );
                rtRequestEntity.setUrl(uri.toString());
                rtRequestEntity.setHeaders(generateHeader(uri, station));

                InetSocketAddress proxyAddress = CommonUtility.constructProxyAddress(uri, station, apimPath);
                if (proxyAddress != null) {
                    rtRequestEntity.setProxyAddress(String.format("%s:%s", proxyAddress.getHostString(), proxyAddress.getPort()));
                }

                // regenerate paaInviaRT payload info
                // extract data from old paaInviaRT
                String oldReceipt = new String(ZipUtil.unzip(AppBase64Util.base64Decode(rtRequestEntity.getPayload())));

                SOAPMessage msg = jaxbElementUtil.getMessage(oldReceipt);

                IntestazionePPT oldReceiptHeader = jaxbElementUtil.getHeader(msg, IntestazionePPT.class);

                String domainId = oldReceiptHeader.getIdentificativoDominio();
                String iuv = oldReceiptHeader.getIdentificativoUnivocoVersamento();

                // retrieve the needed RPT
                List<RPTContentDTO> rpts = ReceiptService.extractRequiredRPTs(sessionData, iuv, domainId);
                int overrideId = 1;
                for (RPTContentDTO rpt : rpts) {

                    // Re-generate the RT payload in order to actualize values and structural errors
                    // If newly-generated payload is not null, the data in 'receipt-rt' is updated with extracted data
                    String newlyGeneratedPayload = regenerateReceiptPayload(rtRequestEntity.getPartitionKey(), rtRequestEntity.getReceiptType(), sessionData, rpt, objectFactory);

                    String payload = newlyGeneratedPayload != null ? newlyGeneratedPayload : oldReceipt;

                    // update entity from 'receipt' container with retry 0 and newly-generated payload
                    String rptDomainId = rpt.getRpt().getDomain().getDomainId();
                    String overriddenIdempotencyKey = String.format("%s_%s_%s", idempotencyKeyComponents[0], idempotencyKeyComponents[1], rptDomainId);
                    String overriddenReceiptId = receiptId + "-" + overrideId;
                    rtRequestEntity.setId(overriddenReceiptId);
                    rtRequestEntity.setDomainId(domainId);
                    rtRequestEntity.setIdempotencyKey(overriddenIdempotencyKey);
                    rtRequestEntity.setSessionId(sessionId);
                    rtRequestEntity.setRetry(0);
                    rtRequestEntity.setPayload(AppBase64Util.base64Encode(ZipUtil.zip(payload)));
                    rtRetryRepository.save(rtRequestEntity);

                    String compositedIdForReceipt = String.format("%s_%s", rtRequestEntity.getPartitionKey(), overriddenReceiptId);
                    serviceBusService.sendMessage(compositedIdForReceipt, null);
                    generateRE(null, "Success", InternalStepStatus.RT_SEND_RESCHEDULING_SUCCESS,
                            null, null, null, sessionId, String.format("Generated receipt: %s", overriddenReceiptId));
                    response.getReceiptStatus().add(Pair.of(overriddenReceiptId, "SCHEDULED"));
                    overrideId += 1;
                }
                // remove old receipt
                rtRetryRepository.deleteById(receiptId, new PartitionKey(rtRequestEntity.getPartitionKey()));

            } catch (Exception e) {

                log.error("Reconciliation for receipt id [{}] ended unsuccessfully!", receiptId, e);
                generateRE(Constants.PAA_INVIA_RT, "Failure", InternalStepStatus.RT_SEND_RESCHEDULING_FAILURE, null, null, null, sessionId);

                response.getReceiptStatus().add(Pair.of(receiptId, String.format("FAILED: [%s]", e.getMessage())));
            }
        }

        return response;
    }

    private List<String> generateHeader(URI uri, StationDto station) {
        List<Pair<String, String>> headers = CommonUtility.constructHeadersForPaaInviaRT(uri, station, stationInForwarderPartialPath, forwarderSubscriptionKey);
        List<String> formattedHeaders = new LinkedList<>();
        for (Pair<String, String> header : headers) {
            formattedHeaders.add(header.getFirst() + ":" + header.getSecond());
        }
        return formattedHeaders;
    }

    /**
     * Regenerate receipt payload according to its type (OK|KO)
     */
    private String regenerateReceiptPayload(String date, ReceiptTypeEnum receiptType, SessionDataDTO sessionData, RPTContentDTO rpt,
                                            gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory) throws IOException {
        String payload = null;
        CommonFieldsDTO commonFields = sessionData.getCommonFields();
        ReceiptStatusEnum receiptStatus = ReceiptStatusEnum.SCHEDULED;

        // if receipt is of KO type, the RT regeneration is straightforward using rpt and session data
        if (ReceiptTypeEnum.KO.equals(receiptType)) {
            payload = regenerateKOReceiptPayload(rpt, commonFields, objectFactory, receiptStatus);
        }

        // if receipt is of OK type, the RT regeneration is more complex because the paSendRTV2 request is needed,
        // and it can only be retrieved from RE event
        else {
            payload = regenerateOKReceiptPayload(date, rpt, sessionData, commonFields, objectFactory, receiptStatus);
        }
        return payload;
    }

    /**
     * Regenerate KO receipt payload
     * @param rpt
     * @param commonFields
     * @param objectFactory
     * @param receiptStatus
     * @return payload as String
     */
    private String regenerateKOReceiptPayload(RPTContentDTO rpt, CommonFieldsDTO commonFields, gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory, ReceiptStatusEnum receiptStatus) {
        it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigDataV1Dto configData = configCacheService.getConfigData();
        return receiptService.generateKoRtFromSessionData(rpt.getRpt().getDomain().getDomainId(), rpt.getIuv(), rpt, commonFields, objectFactory, configData.getConfigurations(), receiptStatus);
    }

    /**
     * Regenerate OK receipt payload
     * @param partitionKey
     * @param rpt
     * @param sessionData
     * @param commonFields
     * @param objectFactory
     * @param receiptStatus
     * @return
     * @throws IOException
     */
    private String regenerateOKReceiptPayload(String partitionKey, RPTContentDTO rpt, SessionDataDTO sessionData,
                                              CommonFieldsDTO commonFields, gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory,
                                              ReceiptStatusEnum receiptStatus) throws IOException {
        // first of all: get first occurrence of OK RT 'try-to-send' operation.
        // If no event is found, no RT was sent.
        List<ReEventEntity> events = reEventRepository.findBySessionIdAndStatusAndPartitionKey(partitionKey, sessionData.getCommonFields().getSessionId(), InternalStepStatus.POSITIVE_RT_TRY_TO_SEND_TO_CREDITOR_INSTITUTION.toString());
        for(ReEventEntity event : events) {
            // use operationId used to retrieve the related paSendRTV2 primitives
            List<ReEventEntity> interfaceReqEventOpt = reEventRepository.findFirstInterfaceRequestByPartitionKey(partitionKey, ReceiptController.BP_RECEIPT_OK, event.getOperationId());
            if (!interfaceReqEventOpt.isEmpty()) {

                // get the compressed payload from event and decompress it, parsing a well-formed request
                ReEventEntity reEvent = interfaceReqEventOpt.get(0);
                String unzippedRequest = new String(ZipUtil.unzip(AppBase64Util.base64Decode(reEvent.getCompressedPayload())));
                ReceiptRequest receiptOkRequest = new Gson().fromJson(unzippedRequest, ReceiptRequest.class);

                // now, from request the paSendRTV2 content can be extracted
                // actualize content for correctly handle multibeneficiary carts
                PaSendRTV2Request paSendRTV2 = ReceiptService.extractDataFromPaSendRT(jaxbElementUtil, receiptOkRequest.getContent(), rpt);

                // check if it is the right paSendRTV2
                if (paSendRTV2.getIdPA().equals(rpt.getRpt().getDomain().getDomainId()) && paSendRTV2.getReceipt().getCreditorReferenceId().equals(rpt.getIuv())) {
                    // finally, use the extracted paSendRTV2 content to re-generate paaInviaRT request
                    IntestazionePPT intestazionePPT = ReceiptService.generateHeader(
                            paSendRTV2.getIdPA(),
                            paSendRTV2.getReceipt().getCreditorReferenceId(),
                            rpt.getRpt().getTransferData().getCcp(),
                            commonFields.getCreditorInstitutionBrokerId(),
                            commonFields.getStationId()
                    );
                    ReceiptContentDTO receiptContent = receiptService.generateOkRtFromSessionData(rpt, paSendRTV2, intestazionePPT, commonFields, objectFactory, receiptStatus);
                    return receiptContent.getPaaInviaRTPayload();
                }
            }
        }

        return null;
    }
}