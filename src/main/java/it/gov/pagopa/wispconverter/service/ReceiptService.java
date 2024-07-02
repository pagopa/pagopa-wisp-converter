package it.gov.pagopa.wispconverter.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.telematici.pagamenti.ws.nodoperpa.ppthead.IntestazionePPT;
import gov.telematici.pagamenti.ws.pafornode.CtReceiptV2;
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
import it.gov.pagopa.wispconverter.repository.model.enumz.ReceiptTypeEnum;
import it.gov.pagopa.wispconverter.service.mapper.RTMapper;
import it.gov.pagopa.wispconverter.service.model.CachedKeysMapping;
import it.gov.pagopa.wispconverter.service.model.ReceiptDto;
import it.gov.pagopa.wispconverter.service.model.re.ReEventDto;
import it.gov.pagopa.wispconverter.service.model.session.CommonFieldsDTO;
import it.gov.pagopa.wispconverter.service.model.session.RPTContentDTO;
import it.gov.pagopa.wispconverter.service.model.session.SessionDataDTO;
import it.gov.pagopa.wispconverter.util.*;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.soap.SOAPMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static it.gov.pagopa.wispconverter.util.Constants.PAA_INVIA_RT;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReceiptService {

    private final RTMapper rtMapper;

    private final JaxbElementUtil jaxbElementUtil;

    private final ConfigCacheService configCacheService;

    private final RptCosmosService rptCosmosService;

    private final RtCosmosService rtCosmosService;

    private final RPTExtractorService rptExtractorService;

    private final ReService reService;

    private final DecouplerService decouplerService;

    private final IdempotencyService idempotencyService;

    private final PaaInviaRTSenderService paaInviaRTSenderService;

    private final ServiceBusService serviceBusService;

    private final ObjectMapper mapper;

    @Value("${wisp-converter.station-in-gpd.partial-path}")
    private String stationInGpdPartialPath;

    @Value("${wisp-converter.station-in-forwarder.partial-path}")
    private String stationInForwarderPartialPath;

    @Value("${wisp-converter.rt-send.scheduling-time-in-hours}")
    private Integer schedulingTimeInHours;


    public void sendKoPaaInviaRtToCreditorInstitution(String payload) {

        try {

            // map the received payload as a list of receipts that will be lately evaluated
            List<ReceiptDto> receipts = List.of(mapper.readValue(payload, ReceiptDto[].class));
            gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory = new gov.telematici.pagamenti.ws.papernodo.ObjectFactory();

            // retrieve configuration data from cache
            ConfigDataV1Dto configData = configCacheService.getConfigData();
            Map<String, ConfigurationKeyDto> configurations = configData.getConfigurations();
            Map<String, StationDto> stations = configData.getStations();


            // generate and send a KO RT for each receipt received in the payload
            for (ReceiptDto receipt : receipts) {

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
                if (CommonUtility.isStationOnboardedOnGpd(configCacheService, sessionData, stationInGpdPartialPath)) {

                    generateREForNotGenerableRT(sessionData, cachedMapping.getIuv(), noticeNumber);

                } else {

                    // generate the header for the paaInviaRT SOAP request. This object is common for each generated request
                    IntestazionePPT header = generateHeader(
                            cachedMapping.getFiscalCode(),
                            cachedMapping.getIuv(),
                            receipt.getPaymentToken(),
                            commonFields.getCreditorInstitutionBrokerId(),
                            commonFields.getStationId());

                    /*
                      For each RPT extracted from session data, is necessary to generate a single paaInviaRT SOAP request.
                      Each paaInviaRT generated will be autonomously sent to creditor institution in order to track each RPT.
                     */
                    for (RPTContentDTO rpt : sessionData.getAllRPTs()) {

                        // Generating the paaInviaRT payload from the RPT
                        String paymentOutcome = "Annullato da WISP"; // TODO change this with one of the following -> https://pagopa.atlassian.net/wiki/spaces/PN5/pages/913244345/WISP-Converter?focusedCommentId=1002078407
                        JAXBElement<CtRicevutaTelematica> generatedReceipt = new ObjectFactory()
                                .createRT(generateRTContentForKoReceipt(rpt, configurations, Instant.now(), paymentOutcome));
                        String rawGeneratedReceipt = jaxbElementUtil.objectToString(generatedReceipt);
                        String paaInviaRtPayload = generatePayloadAsRawString(header, null, rawGeneratedReceipt, objectFactory);

                        // retrieve station from common station identifier
                        StationDto station = stations.get(commonFields.getStationId());

                        // send receipt to the creditor institution and, if not correctly sent, add to queue for retry
                        sendReceiptToCreditorInstitution(sessionData, paaInviaRtPayload, receipt, rpt.getIuv(), noticeNumber, station, true);
                    }
                }
            }

        } catch (JsonProcessingException e) {

            throw new AppException(AppErrorCodeMessageEnum.PARSING_INVALID_BODY, e.getMessage());

        } catch (AppException e) {

            throw e;

        } catch (Exception e) {

            throw new AppException(AppErrorCodeMessageEnum.RECEIPT_KO_NOT_GENERATED, e);
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
            if (CommonUtility.isStationOnboardedOnGpd(configCacheService, sessionData, stationInGpdPartialPath)) {

                generateREForNotGenerableRT(sessionData, cachedMapping.getIuv(), noticeNumber);

            } else {

                /*
                  For each RPT extracted from session data, is necessary to generate a single paaInviaRT SOAP request.
                  Each paaInviaRT generated will be autonomously sent to creditor institution in order to track each RPT.
                */
                for (RPTContentDTO rpt : sessionData.getAllRPTs()) {

                    // generate the header for the paaInviaRT SOAP request. This object is different for each generated request
                    IntestazionePPT intestazionePPT = generateHeader(
                            receipt.getFiscalCode(),
                            receipt.getCreditorReferenceId(),
                            rpt.getRpt().getTransferData().getCcp(),
                            commonFields.getCreditorInstitutionBrokerId(),
                            commonFields.getStationId()
                    );

                    // Generating the paaInviaRT payload from the RPT
                    JAXBElement<CtRicevutaTelematica> generatedReceipt = new it.gov.digitpa.schemas._2011.pagamenti.ObjectFactory()
                            .createRT(generateRTContentForOkReceipt(rpt, paSendRTV2Request));
                    String rawGeneratedReceipt = jaxbElementUtil.objectToString(generatedReceipt);
                    String paaInviaRtPayload = generatePayloadAsRawString(intestazionePPT, commonFields.getSignatureType(), rawGeneratedReceipt, objectFactory);

                    // send receipt to the creditor institution and, if not correctly sent, add to queue for retry
                    sendReceiptToCreditorInstitution(sessionData, paaInviaRtPayload, receipt, rpt.getIuv(), noticeNumber, station, false);
                }
            }

        } catch (AppException e) {

            throw e;

        } catch (Exception e) {

            throw new AppException(AppErrorCodeMessageEnum.RECEIPT_OK_NOT_GENERATED, e);
        }
    }

    private SessionDataDTO getSessionDataFromCachedKeys(CachedKeysMapping cachedMapping) {

        // retrieve cached session identifier form
        String cachedSessionId = decouplerService.getCachedSessionId(cachedMapping.getFiscalCode(), cachedMapping.getIuv());

        // after the retrieve of the session identifier from cache, try to retrieve the RPT previously persisted in storage
        RPTRequestEntity rptRequestEntity = rptCosmosService.getRPTRequestEntity(cachedSessionId);

        // use the retrieved RPT for generate session data information on which the next execution will operate
        return this.rptExtractorService.extractSessionData(rptRequestEntity.getPrimitive(), rptRequestEntity.getPayload());
    }

    private void sendReceiptToCreditorInstitution(SessionDataDTO sessionData, String rawPayload, Object receipt,
                                                  String iuv, String noticeNumber, StationDto station, boolean mustSendNegativeRT) {

        /*
          From station identifier (the common one defined, not the payment reference), retrieve the data
          from the cache and then generate the URL that will be used to send the paaInviaRT SOAP request.
        */
        ConnectionDto stationConnection = station.getConnection();
        String url = CommonUtility.constructUrl(
                stationConnection.getProtocol().getValue(),
                stationConnection.getIp(),
                stationConnection.getPort().intValue(),
                station.getService() != null ? station.getService().getPath() : "",
                null,
                null
        );
        List<Pair<String, String>> headers = CommonUtility.constructHeadersForPaaInviaRT(url, station, stationInForwarderPartialPath);

        // idempotency key creation to check if the rt has already been sent
        String idempotencyKey = sessionData.getCommonFields().getSessionId() + "_" + noticeNumber;

        // send to creditor institution only if another receipt wasn't already sent
        ReceiptTypeEnum receiptType = mustSendNegativeRT ? ReceiptTypeEnum.KO : ReceiptTypeEnum.OK;
        if (idempotencyService.isIdempotencyKeyProcessable(idempotencyKey, receiptType)) {

            // lock idempotency key status to avoid concurrency issues
            idempotencyService.lockIdempotencyKey(idempotencyKey, receiptType);

            // Save an RE event in order to track the sending RT operation
            generateREForSendingRT(mustSendNegativeRT, sessionData, receipt, iuv, noticeNumber);

            // finally, send the receipt to the creditor institution
            IdempotencyStatusEnum idempotencyStatus;
            try {

                // send the receipt to the creditor institution via the URL set in the station configuration
                paaInviaRTSenderService.sendToCreditorInstitution(url, headers, rawPayload);

                // generate a new event in RE for store the successful sending of the receipt
                generateREForSentRT(sessionData, iuv, noticeNumber);

                idempotencyStatus = IdempotencyStatusEnum.SUCCESS;

            } catch (Exception e) {

                // generate a new event in RE for store the unsuccessful sending of the receipt
                generateREForNotSentRT(sessionData, iuv, noticeNumber, e.getMessage());

                // because of the not sent receipt, it is necessary to schedule a retry of the sending process for this receipt
                scheduleRTSend(sessionData, url, headers, rawPayload, station, iuv, noticeNumber, idempotencyKey, receiptType);

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

    private IntestazionePPT generateHeader(String creditorInstitutionId, String iuv, String ccp, String brokerId, String stationId) {

        gov.telematici.pagamenti.ws.nodoperpa.ppthead.ObjectFactory objectFactoryHead = new gov.telematici.pagamenti.ws.nodoperpa.ppthead.ObjectFactory();
        IntestazionePPT header = objectFactoryHead.createIntestazionePPT();
        header.setIdentificativoDominio(creditorInstitutionId);
        header.setIdentificativoUnivocoVersamento(iuv);
        header.setCodiceContestoPagamento(ccp);
        header.setIdentificativoIntermediarioPA(brokerId);
        header.setIdentificativoStazioneIntermediarioPA(stationId);
        return header;
    }

    private String generatePayloadAsRawString(IntestazionePPT header, String signatureType, String receiptContent, gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory) {

        // Generate paaInviaRT object, as JAXB element, with the RT in base64 format
        PaaInviaRT paaInviaRT = objectFactory.createPaaInviaRT();
        paaInviaRT.setRt(receiptContent.getBytes(StandardCharsets.UTF_8));
        paaInviaRT.setTipoFirma(signatureType);
        JAXBElement<PaaInviaRT> paaInviaRTJaxb = objectFactory.createPaaInviaRT(paaInviaRT);

        // generating a SOAP message, including body and header, and then extract the raw string of the envelope
        SOAPMessage message = jaxbElementUtil.newMessage();
        jaxbElementUtil.addBody(message, paaInviaRTJaxb, PaaInviaRT.class);
        jaxbElementUtil.addHeader(message, header, IntestazionePPT.class);
        return jaxbElementUtil.toString(message);
    }


    private void scheduleRTSend(SessionDataDTO sessionData, String url, List<Pair<String, String>> headers, String payload, StationDto station,
                                String iuv, String noticeNumber, String idempotencyKey, ReceiptTypeEnum receiptType) {

        try {

            List<String> formattedHeaders = new LinkedList<>();
            for (Pair<String, String> header : headers) {
                formattedHeaders.add(header.getFirst() + ":" + header.getSecond());
            }

            // generate the RT to be persisted in storage, then save in the same storage
            RTRequestEntity rtRequestEntity = RTRequestEntity.builder()
                    .id(station.getBrokerCode() + "_" + UUID.randomUUID())
                    .primitive(PAA_INVIA_RT)
                    .partitionKey(LocalDate.ofInstant(Instant.now(), ZoneId.systemDefault()).toString())
                    .payload(AppBase64Util.base64Encode(ZipUtil.zip(payload)))
                    .url(url)
                    .headers(formattedHeaders)
                    .retry(0)
                    .idempotencyKey(idempotencyKey)
                    .receiptType(receiptType)
                    .build();
            rtCosmosService.saveRTRequestEntity(rtRequestEntity);

            // after the RT persist, send a message on the service bus
            serviceBusService.sendMessage(rtRequestEntity.getPartitionKey() + "_" + rtRequestEntity.getId(), schedulingTimeInHours);

            // generate a new event in RE for store the successful scheduling of the RT send
            generateREForSuccessfulSchedulingSentRT(sessionData, iuv, noticeNumber);

        } catch (Exception e) {

            // generate a new event in RE for store the unsuccessful scheduling of the RT send
            generateREForFailedSchedulingSentRT(sessionData, iuv, noticeNumber, e);
        }
    }


    private void generateREForNotGenerableRT(SessionDataDTO sessionData, String iuv, String noticeNumber) {

        // extract psp on which the payment will be sent
        RPTContentDTO rptContent = sessionData.getRPTByIUV(iuv);
        String psp = rptContent.getRpt().getPayeeInstitution().getSubjectUniqueIdentifier().getCode();

        // creating event to be persisted for RE
        generateRE(InternalStepStatus.NEGATIVE_RT_NOT_GENERABLE_FOR_GPD_STATION, iuv, noticeNumber, rptContent.getCcp(), psp, null);
    }

    private void generateREForSentRT(SessionDataDTO sessionData, String iuv, String noticeNumber) {

        // extract psp on which the payment will be sent
        RPTContentDTO rptContent = sessionData.getRPTByIUV(iuv);
        String psp = rptContent.getRpt().getPayeeInstitution().getSubjectUniqueIdentifier().getCode();

        // creating event to be persisted for RE
        generateRE(InternalStepStatus.RT_SEND_SUCCESS, iuv, noticeNumber, rptContent.getCcp(), psp, null);
    }

    private void generateREForNotSentRT(SessionDataDTO sessionData, String iuv, String noticeNumber, String otherInfo) {

        // extract psp on which the payment will be sent
        RPTContentDTO rptContent = sessionData.getRPTByIUV(iuv);
        String psp = rptContent.getRpt().getPayeeInstitution().getSubjectUniqueIdentifier().getCode();

        // creating event to be persisted for RE
        generateRE(InternalStepStatus.RT_SEND_FAILURE, iuv, noticeNumber, rptContent.getCcp(), psp, otherInfo);
    }

    private void generateREForSuccessfulSchedulingSentRT(SessionDataDTO sessionData, String iuv, String noticeNumber) {

        // extract psp on which the payment will be sent
        RPTContentDTO rptContent = sessionData.getRPTByIUV(iuv);
        String psp = rptContent.getRpt().getPayeeInstitution().getSubjectUniqueIdentifier().getCode();

        // creating event to be persisted for RE
        generateRE(InternalStepStatus.RT_SEND_SCHEDULING_SUCCESS, iuv, noticeNumber, rptContent.getCcp(), psp, null);
    }

    private void generateREForFailedSchedulingSentRT(SessionDataDTO sessionData, String iuv, String noticeNumber, Throwable e) {

        // extract psp on which the payment will be sent
        RPTContentDTO rptContent = sessionData.getRPTByIUV(iuv);
        String psp = rptContent.getRpt().getPayeeInstitution().getSubjectUniqueIdentifier().getCode();

        // creating event to be persisted for RE
        String otherInfo = "Caused by: " + e.getMessage();
        generateRE(InternalStepStatus.RT_SEND_SCHEDULING_FAILURE, iuv, noticeNumber, rptContent.getCcp(), psp, otherInfo);
    }

    private void generateREForSendingRT(boolean mustSendNegativeRT, SessionDataDTO sessionData, Object receipt, String iuv, String noticeNumber) {

        InternalStepStatus status = mustSendNegativeRT ? InternalStepStatus.NEGATIVE_RT_TRY_TO_SEND_TO_CREDITOR_INSTITUTION : InternalStepStatus.POSITIVE_RT_TRY_TO_SEND_TO_CREDITOR_INSTITUTION;
        String receiptInfo = "Trying to send receipt from " + receipt.toString();
        generateREForSendRTProcess(sessionData, iuv, noticeNumber, status, receiptInfo);
    }

    private void generateREForSendRTProcess(SessionDataDTO sessionData, String iuv, String noticeNumber, InternalStepStatus status, String info) {

        // extract psp on which the payment will be sent
        RPTContentDTO rptContent = sessionData.getRPTByIUV(iuv);
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

}
