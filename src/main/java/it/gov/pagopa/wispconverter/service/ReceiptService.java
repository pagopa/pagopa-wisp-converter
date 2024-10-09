package it.gov.pagopa.wispconverter.service;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.model.RPTRequestEntity;
import it.gov.pagopa.wispconverter.repository.model.RTRequestEntity;
import it.gov.pagopa.wispconverter.repository.model.enumz.IdempotencyStatusEnum;
import it.gov.pagopa.wispconverter.repository.model.enumz.InternalStepStatus;
import it.gov.pagopa.wispconverter.repository.model.enumz.ReceiptStatusEnum;
import it.gov.pagopa.wispconverter.repository.model.enumz.ReceiptTypeEnum;
import it.gov.pagopa.wispconverter.service.mapper.RTMapper;
import it.gov.pagopa.wispconverter.service.model.CachedKeysMapping;
import it.gov.pagopa.wispconverter.service.model.ReceiptDto;
import it.gov.pagopa.wispconverter.service.model.re.ReEventDto;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
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

import static it.gov.pagopa.wispconverter.util.Constants.PAA_INVIA_RT;
import static it.gov.pagopa.wispconverter.util.Constants.PPT_HEAD;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReceiptService {

    private final RTMapper rtMapper;

    private final JaxbElementUtil jaxbElementUtil;

    private final ConfigCacheService configCacheService;

    private final RptCosmosService rptCosmosService;

    private final RtReceiptCosmosService rtReceiptCosmosService;

    private final RtRetryComosService rtRetryComosService;

    private final RPTExtractorService rptExtractorService;

    private final ReService reService;

    private final DecouplerService decouplerService;

    private final IdempotencyService idempotencyService;

    private final PaaInviaRTSenderService paaInviaRTSenderService;

    private final ServiceBusService serviceBusService;

    private final it.gov.pagopa.gen.wispconverter.client.decouplercaching.invoker.ApiClient decouplerCachingClient;

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

    public static List<RPTContentDTO> extractRequiredRPTs(SessionDataDTO sessionData, String iuv, String creditorInstiutionId) {
        List<RPTContentDTO> rpts;
        if (Boolean.TRUE.equals(sessionData.getCommonFields().getIsMultibeneficiary())) {
            rpts = sessionData.getAllRPTs().stream().toList();
        } else {
            rpts = sessionData.getAllRPTs().stream()
                    .filter(rpt -> rpt.getIuv().equals(iuv) && rpt.getRpt().getDomain().getDomainId().equals(creditorInstiutionId))
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

    /**
     * @param payload a list of {@link ReceiptDto} elements
     * @deprecated use {@code sendKoPaaInviaRtToCreditorInstitution(List<ReceiptDto> receipts)} method instead
     */
    @Deprecated(forRemoval = false)
    public void sendKoPaaInviaRtToCreditorInstitution(String payload) {
        List<ReceiptDto> receipts;
        try {
            receipts = List.of(mapper.readValue(payload, ReceiptDto[].class));
        } catch (JsonProcessingException e) {
            throw new AppException(AppErrorCodeMessageEnum.PARSING_INVALID_BODY, e.getMessage());
        }
        sendKoPaaInviaRtToCreditorInstitution(receipts);
    }

    /**
     * send a paaInviaRT with a KO. The body is generated from the list of receipts.
     *
     * @param receipts a list of receipts
     */
    public void sendKoPaaInviaRtToCreditorInstitution(List<ReceiptDto> receipts) {
        try {

            // map the received payload as a list of receipts that will be lately evaluated
            gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory = new gov.telematici.pagamenti.ws.papernodo.ObjectFactory();

            // retrieve configuration data from cache
            ConfigDataV1Dto configData = configCacheService.getConfigData();
            Map<String, ConfigurationKeyDto> configurations = configData.getConfigurations();
            Map<String, StationDto> stations = configData.getStations();


            // generate and send a KO RT for each receipt received in the payload
            for (ReceiptDto receipt : receipts) {

                MDCUtil.setReceiptTimerInfoInMDC(receipt.getFiscalCode(), receipt.getNoticeNumber(), null);

                // retrieve the NAV-to-IUV mapping key from Redis, then use the result for retrieve the session data
                String noticeNumber = receipt.getNoticeNumber();
                CachedKeysMapping cachedMapping = decouplerService.getCachedMappingFromNavToIuv(receipt.getFiscalCode(), noticeNumber);
                SessionDataDTO sessionData = getSessionDataFromCachedKeys(cachedMapping);
                CommonFieldsDTO commonFields = sessionData.getCommonFields();

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
                    List<RPTContentDTO> rpts = extractRequiredRPTs(sessionData, cachedMapping.getIuv(), cachedMapping.getFiscalCode());
                    for (RPTContentDTO rpt : rpts) {

                        // generate the header for the paaInviaRT SOAP request. This object is common for each generated request
                        IntestazionePPT header = generateHeader(
                                rpt.getRpt().getDomain().getDomainId(),
                                cachedMapping.getIuv(),
                                rpt.getCcp(),
                                commonFields.getCreditorInstitutionBrokerId(),
                                commonFields.getStationId());

                        // Generating the paaInviaRT payload from the RPT
                        String paymentOutcome = "Annullato da WISP";
                        JAXBElement<CtRicevutaTelematica> generatedReceipt = new ObjectFactory()
                                .createRT(generateRTContentForKoReceipt(rpt, configurations, Instant.now(), paymentOutcome));
                        String rawGeneratedReceipt = jaxbElementUtil.objectToString(generatedReceipt);
                        String paaInviaRtPayload = generatePayloadAsRawString(header, null, rawGeneratedReceipt, objectFactory);

                        // retrieve station from common station identifier
                        StationDto station = stations.get(commonFields.getStationId());

                        ReceiptContentDTO receiptContent = ReceiptContentDTO.builder()
                                .paaInviaRTPayload(paaInviaRtPayload)
                                .rtPayload(rawGeneratedReceipt)
                                .build();

                        // send receipt to the creditor institution and, if not correctly sent, add to queue for retry
                        sendReceiptToCreditorInstitution(sessionData, rpt, receiptContent, receipt, rpt.getIuv(), noticeNumber, station, true);
                    }
                }
            }

        } catch (AppException e) {

            throw e;

        } catch (Exception e) {

            throw new AppException(AppErrorCodeMessageEnum.RECEIPT_KO_NOT_SENT, e);
        }
    }

    public void sendOkPaaInviaRtToCreditorInstitution(String payload) {

        try {

            // map the received payload as a paSendRTV2 SOAP request that will be lately evaluated
            SOAPMessage envelopeElement = jaxbElementUtil.getMessage(payload);
            PaSendRTV2Request paSendRTV2Request = jaxbElementUtil.getBody(envelopeElement, PaSendRTV2Request.class);
            gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory = new gov.telematici.pagamenti.ws.papernodo.ObjectFactory();

            // retrieve configuration data from cache
            ConfigDataV1Dto configData = configCacheService.getConfigData();
            Map<String, StationDto> stations = configData.getStations();

            // retrieve the NAV-to-IUV mapping key from Redis, then use the result for retrieve the session data
            String noticeNumber = paSendRTV2Request.getReceipt().getNoticeNumber();
            CachedKeysMapping cachedMapping = decouplerService.getCachedMappingFromNavToIuv(paSendRTV2Request.getIdPA(), noticeNumber);
            SessionDataDTO sessionData = getSessionDataFromCachedKeys(cachedMapping);
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
                    sendReceiptToCreditorInstitution(sessionData, rpt, receiptContent, receipt, rpt.getIuv(), noticeNumber, station, false);
                }
            }

        } catch (AppException e) {

            throw e;

        } catch (Exception e) {

            throw new AppException(AppErrorCodeMessageEnum.RECEIPT_OK_NOT_SENT, e);
        }
    }

    public String generateKoRtFromSessionData(String creditorInstitutionId, String iuv, RPTContentDTO rpt,
                                              CommonFieldsDTO commonFields, gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory,
                                              Map<String, ConfigurationKeyDto> configurations, ReceiptStatusEnum receiptStatus) {
        // generate the header for the paaInviaRT SOAP request. This object is common for each generated request
        IntestazionePPT header = generateHeader(
                creditorInstitutionId,
                iuv,
                rpt.getCcp(),
                commonFields.getCreditorInstitutionBrokerId(),
                commonFields.getStationId());

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

    public ReceiptContentDTO generateOkRtFromSessionData(RPTContentDTO rpt, PaSendRTV2Request paSendRTV2,
                                              IntestazionePPT intestazionePPT, CommonFieldsDTO commonFields,
                                              gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory, ReceiptStatusEnum receiptStatus) {
        JAXBElement<CtRicevutaTelematica> generatedReceipt = new it.gov.digitpa.schemas._2011.pagamenti.ObjectFactory()
                .createRT(generateRTContentForOkReceipt(rpt, paSendRTV2));
        String rawGeneratedReceipt = jaxbElementUtil.objectToString(generatedReceipt);
        String paaInviaRtPayload = generatePayloadAsRawString(intestazionePPT, commonFields.getSignatureType(), rawGeneratedReceipt, objectFactory);

        // save receipt-rt
        rtReceiptCosmosService.saveRTEntity(commonFields.getSessionId(), rpt, receiptStatus, rawGeneratedReceipt, ReceiptTypeEnum.OK);

        return ReceiptContentDTO.builder()
                .paaInviaRTPayload(paaInviaRtPayload)
                .rtPayload(rawGeneratedReceipt)
                .build();
    }

    public SessionDataDTO getSessionDataFromSessionId(String sessionId) {
        // try to retrieve the RPT previously persisted in storage from the sessionId
        RPTRequestEntity rptRequestEntity = rptCosmosService.getRPTRequestEntity(sessionId);

        // use the retrieved RPT for generate session data information on which the next execution will operate
        return this.rptExtractorService.extractSessionData(rptRequestEntity.getPrimitive(), rptRequestEntity.getPayload());
    }


    private SessionDataDTO getSessionDataFromCachedKeys(CachedKeysMapping cachedMapping) {

        // retrieve cached session identifier form
        String cachedSessionId = decouplerService.getCachedSessionId(cachedMapping.getFiscalCode(), cachedMapping.getIuv());

        // use the retrieved RPT for generate session data information on which the next execution will operate
        return getSessionDataFromSessionId(cachedSessionId);
    }

    private boolean sendReceiptToCreditorInstitution(SessionDataDTO sessionData, RPTContentDTO rpt,
                                                 ReceiptContentDTO receiptContentDTO, Object receipt,
                                                 String iuv, String noticeNumber,
                                                 StationDto station, boolean mustSendNegativeRT) {

        boolean isSuccessful = false;
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

            // Save an RE event in order to track the sending RT operation
            generateREForSendingRT(mustSendNegativeRT, rpt, receipt, iuv, noticeNumber);

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
                isSuccessful = true;

            } catch (Exception e) {

                // generate a new event in RE for store the unsuccessful sending of the receipt
                String message = e.getMessage();
                if (e instanceof AppException appException) {
                    message = appException.getError().getDetail();
                }

                log.error("Exception: " + AppErrorCodeMessageEnum.RECEIPT_KO_NOT_GENERATED_BUT_MAYBE_RESCHEDULED.getDetail());
                generateREForNotSentRT(rpt, iuv, noticeNumber, message);

                // because of the not sent receipt, it is necessary to schedule a retry of the sending process for this receipt
                scheduleRTSend(sessionData, uri, proxyAddress, headers, receiptContentDTO.getPaaInviaRTPayload(), station, rpt, noticeNumber, idempotencyKey, receiptType);
                idempotencyStatus = IdempotencyStatusEnum.FAILED;
            }

            try {
                // Unlock idempotency key after a successful operation
                idempotencyService.unlockIdempotencyKey(idempotencyKey, receiptType, idempotencyStatus);
            } catch (AppException e) {
                log.error("AppException: ", e);
            }
        }

        return isSuccessful;
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

        // populate ctDatiVersamentoRT tag
        CtDatiVersamentoRT ctDatiVersamentoRT = objectFactory.createCtDatiVersamentoRT();
        this.rtMapper.toCtDatiVersamentoRTForKoRT(ctDatiVersamentoRT, rpt.getRpt().getTransferData(), now, paymentOutcome);

        // populate ctRicevutaTelematica tag
        CtRicevutaTelematica ctRicevutaTelematica = objectFactory.createCtRicevutaTelematica();
        this.rtMapper.toCtRicevutaTelematicaNegativa(ctRicevutaTelematica, rpt.getRpt(), now);
        ctRicevutaTelematica.setDominio(ctDominio);
        ctRicevutaTelematica.setIstitutoAttestante(ctIstitutoAttestante);
        ctRicevutaTelematica.setEnteBeneficiario(ctEnteBeneficiario);
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
        this.rtMapper.toCtSoggettoVersante(ctSoggettoVersante, paSendRTV2Request.getReceipt().getPayer());
        if (ctSoggettoVersante.getIdentificativoUnivocoVersante() == null) {
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

    private String generatePayloadAsRawString(IntestazionePPT header, String signatureType, String receiptContent, gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory) {

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


    public void scheduleRTSend(SessionDataDTO sessionData, URI uri, InetSocketAddress proxyAddress, List<Pair<String, String>> headers, String payload,
                               StationDto station, RPTContentDTO rpt, String noticeNumber, String idempotencyKey, ReceiptTypeEnum receiptType) {

        try {

            List<String> formattedHeaders = new LinkedList<>();
            for (Pair<String, String> header : headers) {
                formattedHeaders.add(header.getFirst() + ":" + header.getSecond());
            }

            String proxy = null;
            if (proxyAddress != null) {
                proxy = String.format("%s:%s", proxyAddress.getHostString(), proxyAddress.getPort());
            }

            // generate the RT to be persisted in storage, then save in the same storage
            RTRequestEntity rtRequestEntity = RTRequestEntity.builder()
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
                    .build();
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
            generateRE(InternalStepStatus.RT_NOT_GENERABLE_FOR_GPD_STATION, iuv, noticeNumber, rptContent.getCcp(), psp, null);
        }
    }

    public void generateREForSentRT(RPTContentDTO rptContent, String iuv, String noticeNumber) {

        // extract psp on which the payment will be sent
        String psp = rptContent.getRpt().getPayeeInstitution().getSubjectUniqueIdentifier().getCode();

        // creating event to be persisted for RE
        generateRE(InternalStepStatus.RT_SEND_SUCCESS, iuv, noticeNumber, rptContent.getCcp(), psp, null);
    }

    public void generateREForNotSentRT(RPTContentDTO rptContent, String iuv, String noticeNumber, String otherInfo) {

        // extract psp on which the payment will be sent
        String psp = rptContent.getRpt().getPayeeInstitution().getSubjectUniqueIdentifier().getCode();

        // creating event to be persisted for RE
        generateRE(InternalStepStatus.RT_SEND_FAILURE, iuv, noticeNumber, rptContent.getCcp(), psp, otherInfo);
    }

    private void generateREForSuccessfulSchedulingSentRT(RPTContentDTO rptContent, String iuv, String noticeNumber) {

        // extract psp on which the payment will be sent
        String psp = rptContent.getRpt().getPayeeInstitution().getSubjectUniqueIdentifier().getCode();

        // creating event to be persisted for RE
        generateRE(InternalStepStatus.RT_SEND_SCHEDULING_SUCCESS, iuv, noticeNumber, rptContent.getCcp(), psp, null);
    }

    private void generateREForFailedSchedulingSentRT(RPTContentDTO rptContent, String iuv, String noticeNumber, Throwable e) {

        // extract psp on which the payment will be sent
        String psp = rptContent.getRpt().getPayeeInstitution().getSubjectUniqueIdentifier().getCode();

        // creating event to be persisted for RE
        String otherInfo = "Caused by: " + e.getMessage();
        generateRE(InternalStepStatus.RT_SEND_SCHEDULING_FAILURE, iuv, noticeNumber, rptContent.getCcp(), psp, otherInfo);
    }

    private void generateREForSendingRT(boolean mustSendNegativeRT, RPTContentDTO rpt, Object receipt, String iuv, String noticeNumber) {

        StringBuilder receiptContent = new StringBuilder("Trying to send the following receipt ");
        if (receipt instanceof CtReceiptV2 ctReceiptV2) {
            receiptContent.append(" [OK]: ")
                    .append("{\"receiptId\": \"").append(ctReceiptV2.getReceiptId())
                    .append("\", \"noticeNumber\":\"").append(ctReceiptV2.getNoticeNumber())
                    .append("\", \"fiscalCode\":\"").append(ctReceiptV2.getFiscalCode())
                    .append("\", ...}");
        } else {
            receiptContent.append(" [KO]: ").append(receipt.toString());
        }
        InternalStepStatus status = mustSendNegativeRT ? InternalStepStatus.NEGATIVE_RT_TRY_TO_SEND_TO_CREDITOR_INSTITUTION : InternalStepStatus.POSITIVE_RT_TRY_TO_SEND_TO_CREDITOR_INSTITUTION;
        generateREForSendRTProcess(rpt, iuv, noticeNumber, status, receiptContent.toString());
    }

    private void generateREForSendRTProcess(RPTContentDTO rptContent, String iuv, String noticeNumber, InternalStepStatus status, String info) {

        // extract psp on which the payment will be sent
        String psp = rptContent.getRpt().getPayeeInstitution().getSubjectUniqueIdentifier().getCode();

        // creating event to be persisted for RE
        generateRE(status, iuv, noticeNumber, rptContent.getCcp(), psp, info);
    }

    private void generateRE(InternalStepStatus status, String iuv, String noticeNumber, String ccp, String psp, String otherInfo) {

        // setting data in MDC for next use
        ReEventDto reEvent = ReUtil.getREBuilder()
                .primitive(PAA_INVIA_RT)
                .status(status)
                .iuv(iuv)
                .ccp(ccp)
                .noticeNumber(noticeNumber)
                .psp(psp)
                .info(otherInfo)
                .build();
        reService.addRe(reEvent);
    }

    public void sendRTKoFromSessionId(String sessionId, InternalStepStatus internalStepStatus) {

        log.debug("Processing session id: {}", sessionId);

        // deactivate the sessionId inside the cache
        it.gov.pagopa.gen.wispconverter.client.decouplercaching.api.DefaultApi apiInstance = new it.gov.pagopa.gen.wispconverter.client.decouplercaching.api.DefaultApi(decouplerCachingClient);
        it.gov.pagopa.gen.wispconverter.client.decouplercaching.model.SessionIdDto sessionIdDto = new it.gov.pagopa.gen.wispconverter.client.decouplercaching.model.SessionIdDto();
        sessionIdDto.setSessionId(sessionId);

        // necessary only if rptTimer is triggered, otherwise it has already been removed
        apiInstance.deleteSessionId(sessionIdDto, MDC.get(Constants.MDC_REQUEST_ID));

        // log event
        MDC.put(Constants.MDC_SESSION_ID, sessionId);
        generateRE(internalStepStatus, null, null, null, null, "A Negative sendRT will be sent: " + sessionId);

        SessionDataDTO sessionDataDTO = getSessionDataFromSessionId(sessionId);

        gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory = new gov.telematici.pagamenti.ws.papernodo.ObjectFactory();

        // retrieve configuration data from cache
        it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigDataV1Dto configData = configCacheService.getConfigData();
        Map<String, it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigurationKeyDto> configurations = configData.getConfigurations();
        Map<String, StationDto> stations = configData.getStations();

        for (RPTContentDTO rpt : sessionDataDTO.getRpts().values()) {

            String domainId = rpt.getRpt().getDomain().getDomainId();
            String iuv = rpt.getIuv();

            // idempotency key creation to check if the rt has already been sent
            String idempotencyKey = IdempotencyService.generateIdempotencyKeyId(sessionDataDTO.getCommonFields().getSessionId(), iuv, domainId);
            idempotencyService.lockIdempotencyKey(idempotencyKey, ReceiptTypeEnum.KO);

            String rtRawPayload = generateKoRtFromSessionData(
                    domainId,
                    rpt.getIuv(),
                    rpt,
                    sessionDataDTO.getCommonFields(),
                    objectFactory,
                    configurations,
                    ReceiptStatusEnum.SENDING);
            StationDto station = stations.get(sessionDataDTO.getCommonFields().getStationId());
            ConnectionDto stationConnection = station.getConnection();
            URI uri = CommonUtility.constructUrl(
                    stationConnection.getProtocol().getValue(),
                    stationConnection.getIp(),
                    stationConnection.getPort().intValue(),
                    station.getService() != null ? station.getService().getPath() : ""
            );
            List<Pair<String, String>> headers = CommonUtility.constructHeadersForPaaInviaRT(uri, station, stationInForwarderPartialPath, forwarderSubscriptionKey);
            InetSocketAddress proxyAddress = CommonUtility.constructProxyAddress(uri, station, apimPath);
            IdempotencyStatusEnum idempotencyStatus;
            try {

                // send the receipt to the creditor institution via the URL set in the station configuration
                String ccp = rpt.getCcp();
                paaInviaRTSenderService.sendToCreditorInstitution(uri, proxyAddress, headers, rtRawPayload, domainId, iuv, ccp);

                // generate a new event in RE for store the successful sending of the receipt
                generateREForSentRT(rpt, rpt.getIuv(), null);
                idempotencyStatus = IdempotencyStatusEnum.SUCCESS;

            } catch (Exception e) {

                // generate a new event in RE for store the unsuccessful sending of the receipt
                String messageException = e.getMessage();
                if (e instanceof AppException appException) {
                    messageException = appException.getError().getDetail();
                }

                log.error("Exception: " + AppErrorCodeMessageEnum.RECEIPT_KO_NOT_GENERATED_BUT_MAYBE_RESCHEDULED.getDetail());
                generateREForNotSentRT(rpt, rpt.getIuv(), null, messageException);

                // because of the not sent receipt, it is necessary to schedule a retry of the sending process for this receipt
                scheduleRTSend(sessionDataDTO, uri, proxyAddress, headers, rtRawPayload, station, rpt, null, idempotencyKey, ReceiptTypeEnum.KO);
                idempotencyStatus = IdempotencyStatusEnum.FAILED;
            }

            try {
                // Unlock idempotency key after a successful operation
                idempotencyService.unlockIdempotencyKey(idempotencyKey, ReceiptTypeEnum.KO, idempotencyStatus);
            } catch (AppException e) {
                log.error("AppException: ", e);
            }
        }
        MDC.clear();
    }
}
