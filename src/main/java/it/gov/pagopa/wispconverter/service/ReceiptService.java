package it.gov.pagopa.wispconverter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.telematici.pagamenti.ws.nodoperpa.ppthead.IntestazionePPT;
import gov.telematici.pagamenti.ws.pafornode.CtReceiptV2;
import gov.telematici.pagamenti.ws.pafornode.CtTransferListPAReceiptV2;
import gov.telematici.pagamenti.ws.pafornode.CtTransferPAReceiptV2;
import gov.telematici.pagamenti.ws.pafornode.PaSendRTV2Request;
import gov.telematici.pagamenti.ws.papernodo.PaaInviaRT;
import it.gov.digitpa.schemas._2011.pagamenti.*;
import it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigDataV1Dto;
import it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigurationKeyDto;
import it.gov.pagopa.gen.wispconverter.client.cache.model.ConnectionDto;
import it.gov.pagopa.gen.wispconverter.client.cache.model.StationDto;
import it.gov.pagopa.gen.wispconverter.client.decouplercaching.model.SessionIdDto;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.ReceiptDeadLetterRepository;
import it.gov.pagopa.wispconverter.repository.model.RTRequestEntity;
import it.gov.pagopa.wispconverter.repository.model.ReceiptDeadLetterEntity;
import it.gov.pagopa.wispconverter.repository.model.enumz.*;
import it.gov.pagopa.wispconverter.service.mapper.RTMapper;
import it.gov.pagopa.wispconverter.service.model.CachedKeysMapping;
import it.gov.pagopa.wispconverter.service.model.ReceiptDto;
import it.gov.pagopa.wispconverter.service.model.session.CommonFieldsDTO;
import it.gov.pagopa.wispconverter.service.model.session.RPTContentDTO;
import it.gov.pagopa.wispconverter.service.model.session.ReceiptContentDTO;
import it.gov.pagopa.wispconverter.service.model.session.SessionDataDTO;
import it.gov.pagopa.wispconverter.util.*;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.soap.SOAPException;
import jakarta.xml.soap.SOAPMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static it.gov.pagopa.wispconverter.util.Constants.PAA_INVIA_RT;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReceiptService {

    public static final String EXCEPTION = "Exception: ";

    private final RTMapper rtMapper;

    private final JaxbElementUtil jaxbElementUtil;

    private final ConfigCacheService configCacheService;

    private final RtReceiptCosmosService rtReceiptCosmosService;

    private final RtRetryComosService rtRetryComosService;

    private final RPTExtractorService rptExtractorService;

    private final ReService reService;

    private final DecouplerService decouplerService;

    private final IdempotencyService idempotencyService;

    private final PaaInviaRTSenderService paaInviaRTSenderService;

    private final ServiceBusService serviceBusService;

    private final ReceiptDeadLetterRepository receiptDeadLetterRepository;
    
    @Autowired
    private final it.gov.pagopa.gen.wispconverter.client.decouplercaching.invoker.ApiClient decouplerCachingClientWithRetry;

    private final ObjectMapper mapper;

    @Value("${wisp-converter.station-in-gpd.partial-path}")
    private String stationInGpdPartialPath;

    @Value("${wisp-converter.station-in-forwarder.partial-path}")
    private String stationInForwarderPartialPath;

    @Value("${wisp-converter.apim.path}")
    private String apimPath;

    @Value("${wisp-converter.forwarder.api-key}")
    private String forwarderSubscriptionKey;

    @Value("${wisp-converter.rt-send.scheduling-time-in-minutes:60}")
    private Integer schedulingTimeInMinutes;

    public static IntestazionePPT generateHeader(String creditorInstitutionId, String iuv, String ccp, String brokerId, String stationId) {

        gov.telematici.pagamenti.ws.nodoperpa.ppthead.ObjectFactory objectFactoryHead = new gov.telematici.pagamenti.ws.nodoperpa.ppthead.ObjectFactory();
        IntestazionePPT header = objectFactoryHead.createIntestazionePPT();
        header.setIdentificativoDominio(creditorInstitutionId);
        header.setIdentificativoUnivocoVersamento(iuv);
        header.setCodiceContestoPagamento(ccp);
        header.setIdentificativoIntermediarioPA(brokerId);
        header.setIdentificativoStazioneIntermediarioPA(stationId);
        return header;
    }

    public static List<RPTContentDTO> extractRequiredRPTs(SessionDataDTO sessionData, String iuv, String creditorInstitutionId) {
        List<RPTContentDTO> rpts;
        if (Boolean.TRUE.equals(sessionData.getCommonFields().getIsMultibeneficiary())) {
            rpts = sessionData.getAllRPTs().stream().toList();
        } else {
            rpts = sessionData.getAllRPTs().stream()
                    .filter(rpt -> rpt.getIuv().equals(iuv) && rpt.getRpt().getDomain().getDomainId().equals(creditorInstitutionId))
                    .toList();
        }
        return rpts;
    }

    public static PaSendRTV2Request extractDataFromPaSendRT(JaxbElementUtil jaxbElementUtil, String payload, RPTContentDTO rpt) {
        SOAPMessage deepCopyMessage = jaxbElementUtil.getMessage(payload);
        PaSendRTV2Request deepCopySendRTV2 = jaxbElementUtil.getBody(deepCopyMessage, PaSendRTV2Request.class);

        List<CtTransferPAReceiptV2> transfers = deepCopySendRTV2.getReceipt().getTransferList().getTransfer();
        transfers = transfers.stream()
                .filter(transfer -> transfer.getFiscalCodePA().equals(rpt.getRpt().getDomain().getDomainId()))
                .toList();

        BigDecimal amount = transfers.stream()
                .map(CtTransferPAReceiptV2::getTransferAmount)
                .reduce(BigDecimal::add)
                .orElse(deepCopySendRTV2.getReceipt().getPaymentAmount());
        deepCopySendRTV2.getReceipt().setPaymentAmount(amount);
        deepCopySendRTV2.setIdPA(rpt.getRpt().getDomain().getDomainId());

        CtTransferListPAReceiptV2 transferList = new CtTransferListPAReceiptV2();
        transferList.getTransfer().addAll(transfers);
        deepCopySendRTV2.getReceipt().setTransferList(transferList);
        return deepCopySendRTV2;
    }

    private static void replaceOutcomeForErrorResponse(OutcomeEnum outcome) {
        String currentOutcome = MDC.get(Constants.MDC_OUTCOME);
        if (currentOutcome == null || currentOutcome.equals(OutcomeEnum.OK.name())) {
            MDC.put(Constants.MDC_OUTCOME, outcome.name());
        }
    }

    /**
     * send a paaInviaRT with a KO. The body is generated from the list of receipts.
     *
     * @param receipts a list of receipts
     */
    public void sendKoPaaInviaRtToCreditorInstitution(List<ReceiptDto> receipts) {
        try {
            // map the received payload as a list of receipts that will be lately evaluated
            it.gov.pagopa.gen.wispconverter.client.decouplercaching.model.SessionIdDto sessionIdDto = new it.gov.pagopa.gen.wispconverter.client.decouplercaching.model.SessionIdDto();

            // retrieve configuration data from cache
            ConfigDataV1Dto configData = configCacheService.getConfigData();
            Map<String, ConfigurationKeyDto> configurations = configData.getConfigurations();
            Map<String, StationDto> stations = configData.getStations();

            // generate and send a KO RT for each receipt received in the payload
            for (ReceiptDto receipt : receipts) {
                handleSingleReceiptForKOPaaInviaRt(receipt, sessionIdDto, configurations, stations);
            }

        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(AppErrorCodeMessageEnum.RECEIPT_KO_NOT_SENT, e);
        }
    }

    private void handleSingleReceiptForKOPaaInviaRt(ReceiptDto receipt, SessionIdDto sessionIdDto, Map<String, ConfigurationKeyDto> configurations, Map<String, StationDto> stations) {

        // workaround to reuse endpoint service. in this case sessionId = sessionId_fiscalCode_noticeNumber
        sessionIdDto.setSessionId(String.format("%s_%s_%s", receipt.getSessionId(), receipt.getFiscalCode(), receipt.getNoticeNumber()));

        // delete key '2_wisp_timer_hang_{wisp_delete_sessionId}' from cache via APIM call
        this.deleteHangTimerCacheKey(sessionIdDto);
        MDCUtil.setReceiptTimerInfoInMDC(receipt.getFiscalCode(), receipt.getNoticeNumber(), null);

        // retrieve the NAV-to-IUV mapping key from Redis, then use the result for retrieve the session data
        String noticeNumber = receipt.getNoticeNumber();
        String iuv = retrieveIuvFromCache(receipt, noticeNumber);

        // use the session-id for generate session data information on which the next execution will operate
        SessionDataDTO sessionData = rptExtractorService.getSessionDataFromSessionId(receipt.getSessionId());
        CommonFieldsDTO commonFields = sessionData.getCommonFields();

        /*
          Validate the station, checking if exists one with the required segregation code and, if is onboarded on GPD
          has the correct primitive version.
          If it is not onboarded on GPD, it must be used for generate RT to sent to creditor institution via
          institution's custom endpoint.
        */
        if (CommonUtility.isStationOnboardedOnGpd(configCacheService, sessionData, receipt.getFiscalCode(), stationInGpdPartialPath)) {

            generateREForNotGenerableRT(sessionData, iuv, noticeNumber);

        } else {

          /*
           For each RPT extracted from session data that is required by paSendRTV2, is necessary to generate a single paaInviaRT SOAP request.
           Each paaInviaRT generated will be autonomously sent to creditor institution in order to track each RPT.
          */
            List<RPTContentDTO> rpts = extractRequiredRPTs(sessionData, iuv, receipt.getFiscalCode());
            for (RPTContentDTO rpt : rpts) {
                handleSingleRptForSendingKOPaaInviaRpt(configurations, stations, rpt, iuv, commonFields, sessionData, noticeNumber);
            }
        }
    }

    private void handleSingleRptForSendingKOPaaInviaRpt(Map<String, ConfigurationKeyDto> configurations,
                                                        Map<String, StationDto> stations,
                                                        RPTContentDTO rpt,
                                                        String iuv,
                                                        CommonFieldsDTO commonFields,
                                                        SessionDataDTO sessionData,
                                                        String noticeNumber) {

        // generate the header for the paaInviaRT SOAP request. This object is common for each generated request
        IntestazionePPT header = generateHeader(
                rpt.getRpt().getDomain().getDomainId(),
                iuv,
                rpt.getCcp(),
                commonFields.getCreditorInstitutionBrokerId(),
                commonFields.getStationId());

        // Generating the paaInviaRT payload from the RPT
        String paymentOutcome = "Annullato da WISP";
        JAXBElement<CtRicevutaTelematica> generatedReceipt = new ObjectFactory().createRT(generateRTContentForKoReceipt(rpt, configurations, Instant.now(), paymentOutcome));
        String rawGeneratedReceipt = jaxbElementUtil.objectToString(generatedReceipt);
        // map the received payload as a list of receipts that will be lately evaluated
        var objectFactory = new gov.telematici.pagamenti.ws.papernodo.ObjectFactory();
        String paaInviaRtPayload = generatePayloadAsRawString(header, null, rawGeneratedReceipt, objectFactory);

        // retrieve station from common station identifier
        StationDto station = stations.get(commonFields.getStationId());

        ReceiptContentDTO receiptContent = ReceiptContentDTO.builder()
                .paaInviaRTPayload(paaInviaRtPayload)
                .rtPayload(rawGeneratedReceipt)
                .build();

        // send receipt to the creditor institution and, if not correctly sent, add to queue for retry
        sendReceiptToCreditorInstitution(sessionData, rpt, receiptContent, rpt.getIuv(), noticeNumber, station, true);
    }

    private String retrieveIuvFromCache(ReceiptDto receipt, String noticeNumber) {
        CachedKeysMapping cachedMapping = decouplerService.getCachedMappingFromNavToIuv(receipt.getFiscalCode(), noticeNumber);
        return cachedMapping.getIuv();
    }

    public void sendOkPaaInviaRtToCreditorInstitution(String payload) {

        try {

            // map the received payload as a paSendRTV2 SOAP request that will be lately evaluated
            SOAPMessage envelopeElement = jaxbElementUtil.getMessage(payload);
            PaSendRTV2Request paSendRTV2Request = jaxbElementUtil.getBody(envelopeElement, PaSendRTV2Request.class);
            gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory = new gov.telematici.pagamenti.ws.papernodo.ObjectFactory();

            // extracting paymentNote, using it as session-id (they are equals)
            String paymentNote = paSendRTV2Request.getReceipt().getPaymentNote();
            MDC.put(Constants.MDC_SESSION_ID, paymentNote);

            // retrieve configuration data from cache
            ConfigDataV1Dto configData = configCacheService.getConfigData();
            Map<String, StationDto> stations = configData.getStations();

            // retrieve the NAV-to-IUV mapping key from Redis, then use the result for retrieve the session data
            String noticeNumber = paSendRTV2Request.getReceipt().getNoticeNumber();
            CachedKeysMapping cachedMapping = decouplerService.getCachedMappingFromNavToIuv(paSendRTV2Request.getIdPA(), noticeNumber);

            // use session-id for generate session data information on which the next execution will operate
            SessionDataDTO sessionData = rptExtractorService.getSessionDataFromSessionId(paymentNote);
            CommonFieldsDTO commonFields = sessionData.getCommonFields();

            // retrieve station from cache and extract receipt from request
            StationDto station = stations.get(commonFields.getStationId());
            CtReceiptV2 receipt = paSendRTV2Request.getReceipt();

            /*
              Validate the station, checking if exists one with the required segregation code and, if is onboarded on GPD
              has the correct primitive version.
              If it is not onboarded on GPD, it must be used for generate RT to sent to creditor institution via
              institution's custom endpoint.
            */
            if (CommonUtility.isStationOnboardedOnGpd(configCacheService, sessionData, receipt.getFiscalCode(), stationInGpdPartialPath)) {

                generateREForNotGenerableRT(sessionData, cachedMapping.getIuv(), noticeNumber);

            } else {

                /*
                  For each RPT extracted from session data that is required by paSendRTV2, is necessary to generate a single paaInviaRT SOAP request.
                  Each paaInviaRT generated will be autonomously sent to creditor institution in order to track each RPT.
                */
                List<RPTContentDTO> rpts = extractRequiredRPTs(sessionData, receipt.getCreditorReferenceId(), receipt.getFiscalCode());
                // this.rptExtractorService.sendEventForExtractedRPTs(rpts);  commented for more reducing logged statuses
                for (RPTContentDTO rpt : rpts) {

                    // actualize content for correctly handle multibeneficiary carts
                    PaSendRTV2Request deepCopySendRTV2 = extractDataFromPaSendRT(jaxbElementUtil, payload, rpt);

                    // generate the header for the paaInviaRT SOAP request. This object is different for each generated request
                    IntestazionePPT intestazionePPT = generateHeader(
                            deepCopySendRTV2.getIdPA(),
                            deepCopySendRTV2.getReceipt().getCreditorReferenceId(),
                            rpt.getRpt().getTransferData().getCcp(),
                            commonFields.getCreditorInstitutionBrokerId(),
                            commonFields.getStationId()
                    );

                    // Generating the paaInviaRT payload from the RPT
                    ReceiptContentDTO receiptContent = generateOkRtFromSessionData(rpt, deepCopySendRTV2, intestazionePPT, commonFields, objectFactory, ReceiptStatusEnum.SENDING);
                    // send receipt to the creditor institution and, if not correctly sent, add to queue for retry
                    sendReceiptToCreditorInstitution(sessionData, rpt, receiptContent, rpt.getIuv(), noticeNumber, station, false);
                }
            }

        } catch (AppException e) {

            throw e;

        } catch (Exception e) {

            throw new AppException(AppErrorCodeMessageEnum.RECEIPT_OK_NOT_SENT, e);
        }
    }

    public String generateKoRtFromSessionData(
            String creditorInstitutionId,
            String iuv,
            RPTContentDTO rpt,
            CommonFieldsDTO commonFields,
            gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory,
            Map<String, ConfigurationKeyDto> configurations,
            ReceiptStatusEnum receiptStatus) {

        // generate the header for the paaInviaRT SOAP request. This object is common for each generated request
        IntestazionePPT header = generateHeader(creditorInstitutionId, iuv, rpt.getCcp(), commonFields.getCreditorInstitutionBrokerId(), commonFields.getStationId());

        // Generating the paaInviaRT payload from the RPT
        String paymentOutcome = "Annullato da WISP";
        JAXBElement<CtRicevutaTelematica> generatedReceipt = new ObjectFactory()
                .createRT(generateRTContentForKoReceipt(rpt, configurations, Instant.now(), paymentOutcome));
        String rawGeneratedReceipt = jaxbElementUtil.objectToString(generatedReceipt);
        String paaInviaRtPayload = generatePayloadAsRawString(header, null, rawGeneratedReceipt, objectFactory);

        // save receipt-rt
        rtReceiptCosmosService.saveRTEntity(commonFields.getSessionId(), rpt, receiptStatus, rawGeneratedReceipt, ReceiptTypeEnum.KO);

        return paaInviaRtPayload;
    }

    public ReceiptContentDTO generateOkRtFromSessionData(
            RPTContentDTO rpt,
            PaSendRTV2Request paSendRTV2,
            IntestazionePPT intestazionePPT,
            CommonFieldsDTO commonFields,
            gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory,
            ReceiptStatusEnum receiptStatus) {
        JAXBElement<CtRicevutaTelematica> generatedReceipt = new it.gov.digitpa.schemas._2011.pagamenti.ObjectFactory().createRT(generateRTContentForOkReceipt(rpt, paSendRTV2));
        String rawGeneratedReceipt = jaxbElementUtil.objectToString(generatedReceipt);
        String paaInviaRtPayload = generatePayloadAsRawString(intestazionePPT, commonFields.getSignatureType(), rawGeneratedReceipt, objectFactory);

        // save receipt-rt
        rtReceiptCosmosService.saveRTEntity(commonFields.getSessionId(), rpt, receiptStatus, rawGeneratedReceipt, ReceiptTypeEnum.OK);

        return ReceiptContentDTO.builder()
                .paaInviaRTPayload(paaInviaRtPayload)
                .rtPayload(rawGeneratedReceipt)
                .build();
    }

    private void sendReceiptToCreditorInstitution(
            SessionDataDTO sessionData,
            RPTContentDTO rpt,
            ReceiptContentDTO receiptContentDTO,
            String iuv,
            String noticeNumber,
            StationDto station,
            boolean mustSendNegativeRT) {

        String domainId = rpt.getRpt().getDomain().getDomainId();

        /*
          From station identifier (the common one defined, not the payment reference), retrieve the data
          from the cache and then generate the URL that will be used to send the paaInviaRT SOAP request.
        */
        ConnectionDto stationConnection = station.getConnection();
        URI uri = CommonUtility.constructUrl(
                stationConnection.getProtocol().getValue(),
                stationConnection.getIp(),
                stationConnection.getPort().intValue(),
                station.getService() != null ? station.getService().getPath() : ""
        );
        List<Pair<String, String>> headers = CommonUtility.constructHeadersForPaaInviaRT(uri, station, stationInForwarderPartialPath, forwarderSubscriptionKey);
        InetSocketAddress proxyAddress = CommonUtility.constructProxyAddress(uri, station, apimPath);

        // idempotency key creation to check if the rt has already been sent
        String idempotencyKey = IdempotencyService.generateIdempotencyKeyId(sessionData.getCommonFields().getSessionId(), iuv, domainId);

        // send to creditor institution only if another receipt wasn't already sent
        ReceiptTypeEnum receiptType = mustSendNegativeRT ? ReceiptTypeEnum.KO : ReceiptTypeEnum.OK;
        if (idempotencyService.isIdempotencyKeyProcessable(idempotencyKey, receiptType)) {
            // lock idempotency key status to avoid concurrency issues
            idempotencyService.lockIdempotencyKey(idempotencyKey, receiptType);

            // finally, send the receipt to the creditor institution
            IdempotencyStatusEnum idempotencyStatus;
            try {
                // save receipt-rt with status SENDING and rawReceipt
                rtReceiptCosmosService.saveRTEntity(sessionData.getCommonFields().getSessionId(), rpt, ReceiptStatusEnum.SENDING, receiptContentDTO.getRtPayload(), receiptType);

                // send the receipt to the creditor institution via the URL set in the station configuration
                String ccp = rpt.getCcp();
                paaInviaRTSenderService.sendToCreditorInstitution(uri, proxyAddress, headers, receiptContentDTO.getPaaInviaRTPayload(), domainId, iuv, ccp);

                // generate a new event in RE for store the successful sending of the receipt
                generateREForSentRT(rpt, iuv, noticeNumber);
                idempotencyStatus = IdempotencyStatusEnum.SUCCESS;
            } catch (AppException e) {

                MDC.put(Constants.MDC_OUTCOME, OutcomeEnum.SENDING_RT_FAILED.name());
                if (e.getError().equals(AppErrorCodeMessageEnum.RECEIPT_GENERATION_ERROR_DEAD_LETTER)) {
                    try {
                        RTRequestEntity rtRequestEntity = generateRTRequestEntity(sessionData, uri, proxyAddress, headers, receiptContentDTO.getPaaInviaRTPayload(), station, rpt, idempotencyKey, receiptType);
                        receiptDeadLetterRepository.save(mapper.convertValue(rtRequestEntity, ReceiptDeadLetterEntity.class));
                        generateREDeadLetter(rpt, noticeNumber, WorkflowStatus.RT_SEND_MOVED_IN_DEADLETTER, null);
                    } catch (IOException ex) {
                        log.error("[DEADLETTER-500][sessionId:{}] {}", sessionData.getCommonFields().getSessionId(), AppErrorCodeMessageEnum.PERSISTENCE_SAVING_DEADLETTER_ERROR.getTitle(), ex);
                    }
                } else {
                    // because of the not sent receipt, it is necessary to schedule a retry of the sending process for this receipt
                    scheduleRTSend(sessionData, uri, proxyAddress, headers, receiptContentDTO.getPaaInviaRTPayload(), station, rpt, noticeNumber, idempotencyKey, receiptType);
                    log.error(EXCEPTION + AppErrorCodeMessageEnum.RECEIPT_KO_NOT_GENERATED_BUT_MAYBE_RESCHEDULED.getDetail(), e);
                }
                idempotencyStatus = IdempotencyStatusEnum.FAILED;
            } catch (Exception e) {

                // because of the not sent receipt, it is necessary to schedule a retry of the sending process for this receipt
                MDC.put(Constants.MDC_OUTCOME, OutcomeEnum.SENDING_RT_FAILED.name());
                scheduleRTSend(sessionData, uri, proxyAddress, headers, receiptContentDTO.getPaaInviaRTPayload(), station, rpt, noticeNumber, idempotencyKey, receiptType);

                // generate a new event in RE for store the unsuccessful sending of the receipt
                log.error(EXCEPTION + AppErrorCodeMessageEnum.RECEIPT_KO_NOT_GENERATED_BUT_MAYBE_RESCHEDULED.getDetail(), e);
                idempotencyStatus = IdempotencyStatusEnum.FAILED;
            }

            try {
                // Unlock idempotency key after a successful operation
                idempotencyService.unlockIdempotencyKey(idempotencyKey, receiptType, idempotencyStatus);
            } catch (AppException e) {
                log.error("AppException: ", e);
            }
        }

    }

    private CtRicevutaTelematica generateRTContentForKoReceipt(RPTContentDTO rpt, Map<String, ConfigurationKeyDto> configurations, Instant now, String paymentOutcome) {

        it.gov.digitpa.schemas._2011.pagamenti.ObjectFactory objectFactory = new it.gov.digitpa.schemas._2011.pagamenti.ObjectFactory();

        // populate ctIstitutoAttestante and ctIdentificativoUnivoco tag
        CtIstitutoAttestante ctIstitutoAttestante = objectFactory.createCtIstitutoAttestante();
        CtIdentificativoUnivoco ctIdentificativoUnivoco = objectFactory.createCtIdentificativoUnivoco();
        this.rtMapper.toCtIstitutoAttestante(ctIstitutoAttestante, ctIdentificativoUnivoco, configurations);

        // populate ctDominio tag
        CtDominio ctDominio = objectFactory.createCtDominio();
        this.rtMapper.toCtDominio(ctDominio, rpt.getRpt().getDomain());

        // populate ctEnteBeneficiario tag
        CtEnteBeneficiario ctEnteBeneficiario = objectFactory.createCtEnteBeneficiario();
        this.rtMapper.toCtEnteBeneficiario(ctEnteBeneficiario, rpt.getRpt().getPayeeInstitution());

        // populate ctSoggettoPagatore tag
        CtSoggettoPagatore ctSoggettoPagatore = objectFactory.createCtSoggettoPagatore();
        this.rtMapper.toCtSoggettoPagatore(ctSoggettoPagatore, rpt.getRpt().getPayer());

        // populate ctSoggettoVersante tag
        CtSoggettoVersante ctSoggettoVersante = objectFactory.createCtSoggettoVersante();
        this.rtMapper.toCtSoggettoVersante(ctSoggettoVersante, rpt.getRpt().getPayerDelegate());
        if (ctSoggettoVersante.getIdentificativoUnivocoVersante() == null || ctSoggettoVersante.getAnagraficaVersante() == null) {
            ctSoggettoVersante = null;
        }

        // populate ctDatiVersamentoRT tag
        CtDatiVersamentoRT ctDatiVersamentoRT = objectFactory.createCtDatiVersamentoRT();
        this.rtMapper.toCtDatiVersamentoRTForKoRT(ctDatiVersamentoRT, rpt.getRpt().getTransferData(), now, paymentOutcome);

        // populate ctRicevutaTelematica tag
        CtRicevutaTelematica ctRicevutaTelematica = objectFactory.createCtRicevutaTelematica();
        this.rtMapper.toCtRicevutaTelematicaNegativa(ctRicevutaTelematica, rpt.getRpt(), now);
        ctRicevutaTelematica.setDominio(ctDominio);
        ctRicevutaTelematica.setIstitutoAttestante(ctIstitutoAttestante);
        ctRicevutaTelematica.setEnteBeneficiario(ctEnteBeneficiario);
        ctRicevutaTelematica.setSoggettoVersante(ctSoggettoVersante);
        ctRicevutaTelematica.setSoggettoPagatore(ctSoggettoPagatore);
        ctRicevutaTelematica.setDatiPagamento(ctDatiVersamentoRT);

        return ctRicevutaTelematica;
    }

    private CtRicevutaTelematica generateRTContentForOkReceipt(RPTContentDTO rpt, PaSendRTV2Request paSendRTV2Request) {

        it.gov.digitpa.schemas._2011.pagamenti.ObjectFactory objectFactory = new it.gov.digitpa.schemas._2011.pagamenti.ObjectFactory();

        // populate ctIstitutoAttestante tag
        CtIstitutoAttestante ctIstitutoAttestante = objectFactory.createCtIstitutoAttestante();
        this.rtMapper.toCtIstitutoAttestante(ctIstitutoAttestante, paSendRTV2Request);

        // populate ctDominio tag
        CtDominio ctDominio = objectFactory.createCtDominio();
        this.rtMapper.toCtDominio(ctDominio, rpt.getRpt().getDomain());

        // populate ctEnteBeneficiario tag
        CtEnteBeneficiario ctEnteBeneficiario = objectFactory.createCtEnteBeneficiario();
        this.rtMapper.toCtEnteBeneficiario(ctEnteBeneficiario, rpt.getRpt().getPayeeInstitution());

        // populate ctSoggettoPagatore tag
        CtSoggettoPagatore ctSoggettoPagatore = objectFactory.createCtSoggettoPagatore();
        this.rtMapper.toCtSoggettoPagatore(ctSoggettoPagatore, paSendRTV2Request.getReceipt().getDebtor());

        // populate ctSoggettoVersante tag
        CtSoggettoVersante ctSoggettoVersante = objectFactory.createCtSoggettoVersante();
        this.rtMapper.toCtSoggettoVersante(ctSoggettoVersante, rpt.getRpt().getPayerDelegate());
        if (ctSoggettoVersante.getIdentificativoUnivocoVersante() == null || ctSoggettoVersante.getAnagraficaVersante() == null) {
            ctSoggettoVersante = null;
        }

        // populate ctDatiVersamentoRT tag
        CtDatiVersamentoRT ctDatiVersamentoRT = objectFactory.createCtDatiVersamentoRT();
        this.rtMapper.toCtDatiVersamentoRTForOkRT(ctDatiVersamentoRT, rpt.getRpt().getTransferData(), paSendRTV2Request.getReceipt());

        // populate ctRicevutaTelematica tag
        CtRicevutaTelematica ctRicevutaTelematica = objectFactory.createCtRicevutaTelematica();
        this.rtMapper.toCtRicevutaTelematicaPositiva(ctRicevutaTelematica, rpt.getRpt(), paSendRTV2Request);
        ctRicevutaTelematica.setDominio(ctDominio);
        ctRicevutaTelematica.setIstitutoAttestante(ctIstitutoAttestante);
        ctRicevutaTelematica.setEnteBeneficiario(ctEnteBeneficiario);
        ctRicevutaTelematica.setSoggettoPagatore(ctSoggettoPagatore);
        ctRicevutaTelematica.setSoggettoVersante(ctSoggettoVersante);
        ctRicevutaTelematica.setDatiPagamento(ctDatiVersamentoRT);

        return ctRicevutaTelematica;
    }

    private String generatePayloadAsRawString(
            IntestazionePPT header,
            String signatureType,
            String receiptContent,
            gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory) {

        // Generate paaInviaRT object, as JAXB element, with the RT in base64 format
        PaaInviaRT paaInviaRT = objectFactory.createPaaInviaRT();
        paaInviaRT.setRt(receiptContent.getBytes(StandardCharsets.UTF_8));
        paaInviaRT.setTipoFirma(signatureType == null ? "" : signatureType);
        JAXBElement<PaaInviaRT> paaInviaRTJaxb = objectFactory.createPaaInviaRT(paaInviaRT);

        // generating a SOAP message, including body and header, and then extract the raw string of the envelope
        SOAPMessage message = jaxbElementUtil.newMessage();
        try {
            message.getSOAPPart().getEnvelope().removeNamespaceDeclaration("SOAP-ENV");
            message.getSOAPPart().getEnvelope().setPrefix(Constants.SOAP_ENV);
            message.getSOAPPart().getEnvelope().addNamespaceDeclaration(Constants.PPT_HEAD, "http://ws.pagamenti.telematici.gov/ppthead"); // ns2
            message.getSOAPPart().getEnvelope().addNamespaceDeclaration(Constants.PPT, "http://ws.pagamenti.telematici.gov/"); // ns3

            message.getSOAPHeader().setPrefix(Constants.SOAP_ENV);
            message.getSOAPBody().setPrefix(Constants.SOAP_ENV);

            jaxbElementUtil.addBody(message, paaInviaRTJaxb, PaaInviaRT.class);
            message.getSOAPPart().getEnvelope().getBody().getFirstChild().setPrefix(Constants.PPT);

            jaxbElementUtil.addHeader(message, header, IntestazionePPT.class);
            message.getSOAPPart().getEnvelope().getHeader().getFirstChild().setPrefix(Constants.PPT_HEAD);

        } catch (SOAPException e) {
            log.warn("Impossible to set 'soapenv' instead of 'SOAP-ENV' as namespace. ", e);
            jaxbElementUtil.addBody(message, paaInviaRTJaxb, PaaInviaRT.class);
            jaxbElementUtil.addHeader(message, header, IntestazionePPT.class);
        }
        return jaxbElementUtil.toString(message);
    }

    public void scheduleRTSend(
            SessionDataDTO sessionData,
            URI uri,
            InetSocketAddress proxyAddress,
            List<Pair<String, String>> headers,
            String payload,
            StationDto station,
            RPTContentDTO rpt,
            String noticeNumber,
            String idempotencyKey,
            ReceiptTypeEnum receiptType) {

        try {
            // generate the RT to be persisted in storage, then save in the same storage
            RTRequestEntity rtRequestEntity = generateRTRequestEntity(sessionData, uri, proxyAddress, headers, payload, station, rpt, idempotencyKey, receiptType);
            rtRetryComosService.saveRTRequestEntity(rtRequestEntity);

            // after the RT persist, send a message on the service bus
            serviceBusService.sendMessage(rtRequestEntity.getPartitionKey() + "_" + rtRequestEntity.getId(), schedulingTimeInMinutes);

            // generate a new event in RE for store the successful scheduling of the RT send
            generateREForSuccessfulSchedulingSentRT(rpt, rpt.getIuv(), noticeNumber);
            rtReceiptCosmosService.updateReceiptStatus(rpt, ReceiptStatusEnum.SCHEDULED);

        } catch (Exception e) {

            // generate a new event in RE for store the unsuccessful scheduling of the RT send
            generateREForFailedSchedulingSentRT(rpt, rpt.getIuv(), noticeNumber, e);
            rtReceiptCosmosService.updateReceiptStatus(rpt, ReceiptStatusEnum.NOT_SENT);
        }
    }

    private void generateREForNotGenerableRT(SessionDataDTO sessionData, String iuv, String noticeNumber) {

        // extract psp on which the payment will be sent
        List<RPTContentDTO> rpts = sessionData.getRPTByIUV(iuv);
        for (RPTContentDTO rptContent : rpts) {

            String psp = rptContent.getRpt().getPayeeInstitution().getSubjectUniqueIdentifier().getCode();

            // creating event to be persisted for RE
            MDC.put(Constants.MDC_IUV, iuv);
            MDC.put(Constants.MDC_NOTICE_NUMBER, noticeNumber);
            MDC.put(Constants.MDC_PSP_ID, psp);
            MDC.put(Constants.MDC_CCP, rptContent.getCcp());
            reService.sendEvent(WorkflowStatus.RT_SEND_SKIPPED_FOR_GPD_STATION);
            MDC.remove(Constants.MDC_IUV);
            MDC.remove(Constants.MDC_NOTICE_NUMBER);
            MDC.remove(Constants.MDC_PSP_ID);
            MDC.remove(Constants.MDC_CCP);
        }
    }

    public void generateREForSentRT(RPTContentDTO rptContent, String iuv, String noticeNumber) {

        // extract psp on which the payment will be sent
        String psp = rptContent.getRpt().getPayeeInstitution().getSubjectUniqueIdentifier().getCode();

        // creating event to be persisted for RE
        MDC.put(Constants.MDC_IUV, iuv);
        MDC.put(Constants.MDC_NOTICE_NUMBER, noticeNumber);
        MDC.put(Constants.MDC_PSP_ID, psp);
        MDC.put(Constants.MDC_CCP, rptContent.getCcp());
        MDC.put(Constants.MDC_OUTCOME, OutcomeEnum.OK.toString());
        reService.sendEvent(WorkflowStatus.RT_SEND_SUCCESS);
        MDC.remove(Constants.MDC_IUV);
        MDC.remove(Constants.MDC_NOTICE_NUMBER);
        MDC.remove(Constants.MDC_PSP_ID);
        MDC.remove(Constants.MDC_CCP);
    }

    private void generateREForSuccessfulSchedulingSentRT(RPTContentDTO rptContent, String iuv, String noticeNumber) {

        // extract psp on which the payment will be sent
        String psp = rptContent.getRpt().getPayeeInstitution().getSubjectUniqueIdentifier().getCode();

        // creating event to be persisted for RE
        MDC.put(Constants.MDC_IUV, iuv);
        MDC.put(Constants.MDC_NOTICE_NUMBER, noticeNumber);
        MDC.put(Constants.MDC_PSP_ID, psp);
        MDC.put(Constants.MDC_CCP, rptContent.getCcp());
        reService.sendEvent(WorkflowStatus.RT_SEND_SCHEDULING_SUCCESS, "RT send scheduled successfully");
        MDC.remove(Constants.MDC_IUV);
        MDC.remove(Constants.MDC_NOTICE_NUMBER);
        MDC.remove(Constants.MDC_PSP_ID);
        MDC.remove(Constants.MDC_CCP);

        // replace outcome for error response
        replaceOutcomeForErrorResponse(OutcomeEnum.SENDING_RT_FAILED);
    }

    private void generateREForFailedSchedulingSentRT(RPTContentDTO rptContent, String iuv, String noticeNumber, Throwable e) {

        // extract psp on which the payment will be sent
        String psp = rptContent.getRpt().getPayeeInstitution().getSubjectUniqueIdentifier().getCode();

        // creating event to be persisted for RE
        String otherInfo = "RT send not scheduled. Caused by: " + e.getMessage();
        // creating event to be persisted for RE
        MDC.put(Constants.MDC_IUV, iuv);
        MDC.put(Constants.MDC_NOTICE_NUMBER, noticeNumber);
        MDC.put(Constants.MDC_PSP_ID, psp);
        MDC.put(Constants.MDC_CCP, rptContent.getCcp());
        reService.sendEvent(WorkflowStatus.RT_SEND_SCHEDULING_FAILURE, otherInfo);
        MDC.remove(Constants.MDC_IUV);
        MDC.remove(Constants.MDC_NOTICE_NUMBER);
        MDC.remove(Constants.MDC_PSP_ID);
        MDC.remove(Constants.MDC_CCP);

        // replace outcome for error response
        replaceOutcomeForErrorResponse(OutcomeEnum.SENDING_RT_FAILED);
    }

    private void generateREDeadLetter(RPTContentDTO rptContent, String noticeNumber, WorkflowStatus status, String info) {

        // extract psp on which the payment will be sent
        String psp = rptContent.getRpt().getPayeeInstitution().getSubjectUniqueIdentifier().getCode();

        // creating event to be persisted for RE
        MDC.put(Constants.MDC_IUV, rptContent.getIuv());
        MDC.put(Constants.MDC_NOTICE_NUMBER, noticeNumber);
        MDC.put(Constants.MDC_PSP_ID, psp);
        MDC.put(Constants.MDC_CCP, rptContent.getCcp());
        reService.sendEvent(status, info);
        MDC.remove(Constants.MDC_IUV);
        MDC.remove(Constants.MDC_NOTICE_NUMBER);
        MDC.remove(Constants.MDC_PSP_ID);
        MDC.remove(Constants.MDC_CCP);

        // replace outcome for error response
        replaceOutcomeForErrorResponse(OutcomeEnum.SENDING_RT_FAILED);
    }

    public void sendRTKoFromSessionId(String sessionId) {

        // log event
        log.debug("[sendRTKoFromSessionId] Processing session id: {}", sessionId);
        MDC.put(Constants.MDC_SESSION_ID, sessionId);

        // delete key '2_wisp_timer_hang_{wisp_delete_sessionId}' from cache via APIM call
        it.gov.pagopa.gen.wispconverter.client.decouplercaching.model.SessionIdDto sessionIdDto = new it.gov.pagopa.gen.wispconverter.client.decouplercaching.model.SessionIdDto();
        sessionIdDto.setSessionId(sessionId);
        this.deleteHangTimerCacheKey(sessionIdDto);

        SessionDataDTO sessionDataDTO = rptExtractorService.getSessionDataFromSessionId(sessionId);
        gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory = new gov.telematici.pagamenti.ws.papernodo.ObjectFactory();

        // retrieve configuration data from cache
        it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigDataV1Dto configData = configCacheService.getConfigData();
        Map<String, it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigurationKeyDto> configurations = configData.getConfigurations();
        Map<String, StationDto> stations = configData.getStations();

        for (RPTContentDTO rpt : sessionDataDTO.getRpts().values()) {
            handleSingleRptForSendRtKo(rpt, sessionDataDTO, objectFactory, configurations, stations);
        }
    }

    private void handleSingleRptForSendRtKo(RPTContentDTO rpt, SessionDataDTO sessionDataDTO, gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory, Map<String, ConfigurationKeyDto> configurations, Map<String, StationDto> stations) {
        String domainId = rpt.getRpt().getDomain().getDomainId();
        String iuv = rpt.getIuv();

        // idempotency key creation to check if the rt has already been sent
        String idempotencyKey =
                IdempotencyService.generateIdempotencyKeyId(
                        sessionDataDTO.getCommonFields().getSessionId(), iuv, domainId);
        idempotencyService.lockIdempotencyKey(idempotencyKey, ReceiptTypeEnum.KO);

        String rtRawPayload =
                generateKoRtFromSessionData(
                        domainId,
                        rpt.getIuv(),
                        rpt,
                        sessionDataDTO.getCommonFields(),
                        objectFactory,
                        configurations,
                        ReceiptStatusEnum.SENDING);
        StationDto station = stations.get(sessionDataDTO.getCommonFields().getStationId());
        ConnectionDto stationConnection = station.getConnection();
        URI uri =
                CommonUtility.constructUrl(
                        stationConnection.getProtocol().getValue(),
                        stationConnection.getIp(),
                        stationConnection.getPort().intValue(),
                        station.getService() != null ? station.getService().getPath() : "");
        List<Pair<String, String>> headers =
                CommonUtility.constructHeadersForPaaInviaRT(
                        uri, station, stationInForwarderPartialPath, forwarderSubscriptionKey);
        InetSocketAddress proxyAddress = CommonUtility.constructProxyAddress(uri, station, apimPath);
        IdempotencyStatusEnum idempotencyStatus;
        try {

            // send the receipt to the creditor institution via the URL set in the station configuration
            String ccp = rpt.getCcp();
            paaInviaRTSenderService.sendToCreditorInstitution(
                    uri, proxyAddress, headers, rtRawPayload, domainId, iuv, ccp);

            // generate a new event in RE for store the successful sending of the receipt
            generateREForSentRT(rpt, rpt.getIuv(), null);
            idempotencyStatus = IdempotencyStatusEnum.SUCCESS;
        } catch (AppException e) {
            String message = e.getError().getDetail();
            if (e.getError().equals(AppErrorCodeMessageEnum.RECEIPT_GENERATION_ERROR_DEAD_LETTER)) {
                try {
                    RTRequestEntity rtRequestEntity = generateRTRequestEntity(
                            sessionDataDTO,
                            uri,
                            proxyAddress,
                            headers,
                            rtRawPayload,
                            station,
                            rpt,
                            idempotencyKey,
                            ReceiptTypeEnum.KO);
                    receiptDeadLetterRepository.save(mapper.convertValue(rtRequestEntity, ReceiptDeadLetterEntity.class));
                    generateREDeadLetter(rpt, null, WorkflowStatus.RT_SEND_MOVED_IN_DEADLETTER, null);
                } catch (IOException ex) {
                    log.error("[DEADLETTER-500][sessionId:{}] {}", sessionDataDTO.getCommonFields().getSessionId(), AppErrorCodeMessageEnum.PERSISTENCE_SAVING_DEADLETTER_ERROR.getTitle(), ex);
                }
            } else {
                // because of the not sent receipt, it is necessary to schedule a retry of the sending
                // process for this receipt
                scheduleRTSend(
                        sessionDataDTO,
                        uri,
                        proxyAddress,
                        headers,
                        rtRawPayload,
                        station,
                        rpt,
                        null,
                        idempotencyKey,
                        ReceiptTypeEnum.KO);
                log.error(EXCEPTION + AppErrorCodeMessageEnum.RECEIPT_KO_NOT_GENERATED_BUT_MAYBE_RESCHEDULED.getDetail());
            }
            idempotencyStatus = IdempotencyStatusEnum.FAILED;

        } catch (Exception e) {

            // generate a new event in RE for store the unsuccessful sending of the receipt
            String message = e.getMessage();

            // because of the not sent receipt, it is necessary to schedule a retry of the sending
            // process for this receipt
            scheduleRTSend(
                    sessionDataDTO,
                    uri,
                    proxyAddress,
                    headers,
                    rtRawPayload,
                    station,
                    rpt,
                    null,
                    idempotencyKey,
                    ReceiptTypeEnum.KO);

            log.error(EXCEPTION + AppErrorCodeMessageEnum.RECEIPT_KO_NOT_GENERATED_BUT_MAYBE_RESCHEDULED.getDetail());
            idempotencyStatus = IdempotencyStatusEnum.FAILED;
        }

        try {
            // Unlock idempotency key after a successful operation
            idempotencyService.unlockIdempotencyKey(idempotencyKey, ReceiptTypeEnum.KO, idempotencyStatus);
        } catch (AppException e) {
            log.error("AppException: ", e);
        }
    }

    /**
     * sessionIdDto could contain sessionId or sessionId_fiscalCode_noticeNumber
     *
     * @param sessionIdDto
     */
    private void deleteHangTimerCacheKey(it.gov.pagopa.gen.wispconverter.client.decouplercaching.model.SessionIdDto sessionIdDto) {

        // deactivate the sessionId inside the cache
        it.gov.pagopa.gen.wispconverter.client.decouplercaching.api.DefaultApi apiInstance = new it.gov.pagopa.gen.wispconverter.client.decouplercaching.api.DefaultApi(decouplerCachingClientWithRetry);

        CompletableFuture.runAsync(() -> {
            // necessary only if rptTimer is triggered, otherwise it has already been removed
            log.debug("[{}][cache-key={}] delete-hang-timer-cache-key", sessionIdDto.getSessionId(), Instant.now().toString());
            apiInstance.deleteSessionId(sessionIdDto, MDC.get(Constants.MDC_REQUEST_ID));
        }).exceptionally(throwable -> {
            log.error("[cache-key={}] Exception while delete-hang-timer-cache-key: {}", sessionIdDto.getSessionId(), throwable.getMessage());
            return null;
        });
    }

    private RTRequestEntity generateRTRequestEntity(
            SessionDataDTO sessionData,
            URI uri,
            InetSocketAddress proxyAddress,
            List<Pair<String, String>> headers,
            String payload,
            StationDto station,
            RPTContentDTO rpt,
            String idempotencyKey,
            ReceiptTypeEnum receiptType)
            throws IOException {
        List<String> formattedHeaders = new LinkedList<>();
        for (Pair<String, String> header : headers) {
            formattedHeaders.add(header.getFirst() + ":" + header.getSecond());
        }

        String proxy = null;
        if (proxyAddress != null) {
            proxy = String.format("%s:%s", proxyAddress.getHostString(), proxyAddress.getPort());
        }

        // generate the RT
        return RTRequestEntity.builder()
                .id(station.getBrokerCode() + "_" + UUID.randomUUID())
                .domainId(rpt.getRpt().getDomain().getDomainId())
                .iuv(rpt.getIuv())
                .ccp(rpt.getCcp())
                .sessionId(sessionData.getCommonFields().getSessionId())
                .primitive(PAA_INVIA_RT)
                .partitionKey(LocalDate.ofInstant(Instant.now(), ZoneId.systemDefault()).toString())
                .payload(AppBase64Util.base64Encode(ZipUtil.zip(payload)))
                .url(uri.toString())
                .proxyAddress(proxy)
                .headers(formattedHeaders)
                .retry(0)
                .idempotencyKey(idempotencyKey)
                .receiptType(receiptType)
                .station(station.getStationCode())
                .build();
    }
}
