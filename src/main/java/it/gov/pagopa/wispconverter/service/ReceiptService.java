package it.gov.pagopa.wispconverter.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.telematici.pagamenti.ws.nodoperpa.ppthead.IntestazionePPT;
import gov.telematici.pagamenti.ws.pafornode.PaSendRTV2Request;
import gov.telematici.pagamenti.ws.papernodo.PaaInviaRT;
import it.gov.digitpa.schemas._2011.pagamenti.*;
import it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigDataV1Dto;
import it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigurationKeyDto;
import it.gov.pagopa.gen.wispconverter.client.cache.model.PaymentServiceProviderDto;
import it.gov.pagopa.gen.wispconverter.client.cache.model.StationDto;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.model.RPTRequestEntity;
import it.gov.pagopa.wispconverter.repository.model.RTRequestEntity;
import it.gov.pagopa.wispconverter.repository.model.enumz.InternalStepStatus;
import it.gov.pagopa.wispconverter.service.mapper.RTMapper;
import it.gov.pagopa.wispconverter.service.model.CachedKeysMapping;
import it.gov.pagopa.wispconverter.service.model.ReceiptDto;
import it.gov.pagopa.wispconverter.service.model.re.ReEventDto;
import it.gov.pagopa.wispconverter.service.model.session.CommonFieldsDTO;
import it.gov.pagopa.wispconverter.service.model.session.PaymentNoticeContentDTO;
import it.gov.pagopa.wispconverter.service.model.session.RPTContentDTO;
import it.gov.pagopa.wispconverter.service.model.session.SessionDataDTO;
import it.gov.pagopa.wispconverter.util.*;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.soap.SOAPMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static it.gov.pagopa.wispconverter.util.Constants.PA_INVIA_RT;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReceiptService {

    private final RTMapper rtMapper;

    private final JaxbElementUtil jaxbElementUtil;

    private final ConfigCacheService configCacheService;
    private final RptCosmosService rptCosmosService;
    private final RTCosmosService rtCosmosService;
    private final RPTExtractorService rptExtractorService;
    private final ReService reService;
    private final DecouplerService decouplerService;
    private final PaaInviaRTService paaInviaRTService;

    private final PaaInviaRTServiceBusService paaInviaRTServiceBusService;

    private final ObjectMapper mapper;

    @Value("${wisp-converter.station-in-gpd.partial-path}")
    private String stationInGpdPartialPath;


    @Transactional
    public void sendKoPaaInviaRtToCreditorInstitution(String payload) {
        try {
            List<ReceiptDto> receipts = List.of(mapper.readValue(payload, ReceiptDto[].class));
            gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory = new gov.telematici.pagamenti.ws.papernodo.ObjectFactory();

            // retrieve configuration data from cache
            ConfigDataV1Dto configData = configCacheService.getConfigData();
            Map<String, ConfigurationKeyDto> configurations = configData.getConfigurations();
            Map<String, PaymentServiceProviderDto> psps = configData.getPsps();
            Map<String, StationDto> stations = configData.getStations();

            for (ReceiptDto receipt : receipts) {

                // retrieve the NAV-based-key-to-IUV-based-key-map keys from Redis, then use the result for retrieve the IUV-based key
                CachedKeysMapping cachedMapping = decouplerService.getCachedMappingFromNavToIuv(receipt.getFiscalCode(), receipt.getNoticeNumber());
                String cachedSessionId = decouplerService.getCachedSessionId(cachedMapping.getFiscalCode(), cachedMapping.getIuv());

                //
                RPTRequestEntity rptRequestEntity = rptCosmosService.getRPTRequestEntity(cachedSessionId);

                //
                SessionDataDTO sessionData = this.rptExtractorService.extractSessionData(rptRequestEntity.getPrimitive(), rptRequestEntity.getPayload());
                CommonFieldsDTO commonFields = sessionData.getCommonFields();

                /*
                  Validate the station, checking if exists one with the required segregation code and, if is onboarded on GPD
                  has the correct primitive version.
                  If it is not onboarded on GPD, it must be used for generate RT to sent to creditor institution via
                  institution's custom endpoint.
                 */
                if (CommonUtility.isStationOnboardedOnGpd(configCacheService, sessionData, receipt.getNoticeNumber(), stationInGpdPartialPath)) {

                    generateREForNotGenerableRT(sessionData, cachedMapping.getIuv());
                } else {

                }
            }


            receipts.forEach(receipt -> {

                // retrieve the NAV-based-key-to-IUV-based-key-map keys from Redis, then use the result for retrieve the IUV-based key
                CachedKeysMapping cachedMapping = decouplerService.getCachedMappingFromNavToIuv(receipt.getFiscalCode(), receipt.getNoticeNumber());
                String cachedSessionId = decouplerService.getCachedSessionId(cachedMapping.getFiscalCode(), cachedMapping.getIuv());

                /*
                CachedKeysMapping cachedMapping = decouplerService.getCachedMappingFromNavToIuv(paSendRTV2Request.getIdPA(), noticeNumber);
                CachedKeysMapping cachedMapping = decouplerService.getCachedMappingFromNavToIuv(receipt.getFiscalCode(), receipt.getNoticeNumber());


                String cachedSessionId = decouplerService.getCachedSessionId(cachedMapping.getFiscalCode(), cachedMapping.getIuv());
                String cachedSessionId = decouplerService.getCachedSessionId(cachedMapping.getFiscalCode(), cachedMapping.getIuv());
                 */

                RPTRequestEntity rptRequestEntity = rptCosmosService.getRPTRequestEntity(cachedSessionId);

                SessionDataDTO sessionData = this.rptExtractorService.extractSessionData(rptRequestEntity.getPrimitive(), rptRequestEntity.getPayload());
                CommonFieldsDTO commonFields = sessionData.getCommonFields();

                /*
                  Validate the station, checking if exists one with the required segregation code and, if is onboarded on GPD,
                  has the correct primitive version.
                  If it is not onboarded on GPD, it must be used for generate RT to sent to creditor institution via
                  institution's custom endpoint.
                 */
                if (CommonUtility.isStationOnboardedOnGpd(configCacheService, sessionData, receipt.getNoticeNumber(), stationInGpdPartialPath)) {

                    generateREForNotGenerableRT(sessionData, cachedMapping.getIuv());

                } else {

                    IntestazionePPT intestazionePPT = generateIntestazionePPT(
                            cachedMapping.getFiscalCode(),
                            cachedMapping.getIuv(),
                            receipt.getPaymentToken(),
                            commonFields.getCreditorInstitutionBrokerId(),
                            commonFields.getStationId());

                    sessionData.getAllRPTs().forEach(rpt -> {
                        StationDto stationDto = stations.get(commonFields.getStationId());
                        PaymentServiceProviderDto psp = psps.get(rpt.getRpt().getPayeeInstitution().getSubjectUniqueIdentifier().getCode());

                        Instant now = Instant.now();
                        JAXBElement<CtRicevutaTelematica> rt = new it.gov.digitpa.schemas._2011.pagamenti.ObjectFactory().createRT(generateCtRicevutaTelematica(rpt, configurations, now));

                        String paaInviaRTXmlString = generatePaaInviaRTAndTrace(intestazionePPT, jaxbElementUtil.objectToString(rt), objectFactory);

                        String url = CommonUtility.constructUrl(
                                stationDto.getConnection().getProtocol().getValue(),
                                stationDto.getConnection().getIp(),
                                stationDto.getConnection().getPort().intValue(),
                                stationDto.getService().getPath(),
                                null,
                                null
                        );

                        ReEventDto reEventDto = rtCosmosService.generateRE(rptRequestEntity,
                                rpt,
                                cachedMapping.getIuv(),
                                receipt.getPaymentToken(),
                                stationDto.getStationCode(),
                                psp,
                                InternalStepStatus.GENERATED_NEW_RT
                        );
                        reService.addRe(reEventDto);

                        send(url, paaInviaRTXmlString, rptRequestEntity, rpt, cachedMapping.getIuv(), receipt.getPaymentToken(), stationDto, psp);

                    });
                }
            });
        } catch (JsonProcessingException e) {
            throw new AppException(AppErrorCodeMessageEnum.PARSING_INVALID_BODY, e.getMessage());
        } catch (AppException appEx) {
            throw appEx;
        } catch (Exception e) {
            throw new AppException(AppErrorCodeMessageEnum.GENERIC_ERROR, e.getMessage());
        }
    }

    @Transactional
    public void sendOkPaaInviaRtToCreditorInstitution(String payload) {
        try {
            Map<String, StationDto> stations = configCacheService.getConfigData().getStations();
            Map<String, PaymentServiceProviderDto> psps = configCacheService.getConfigData().getPsps();

            SOAPMessage envelopeElement = jaxbElementUtil.getMessage(payload);
            PaSendRTV2Request paSendRTV2Request = jaxbElementUtil.getBody(envelopeElement, PaSendRTV2Request.class);

            String noticeNumber = paSendRTV2Request.getReceipt().getNoticeNumber();
            CachedKeysMapping cachedMapping = decouplerService.getCachedMappingFromNavToIuv(paSendRTV2Request.getIdPA(), noticeNumber);
            String cachedSessionId = decouplerService.getCachedSessionId(cachedMapping.getFiscalCode(), cachedMapping.getIuv());

            RPTRequestEntity rptRequestEntity = rptCosmosService.getRPTRequestEntity(cachedSessionId);

            SessionDataDTO sessionData = this.rptExtractorService.extractSessionData(rptRequestEntity.getPrimitive(), rptRequestEntity.getPayload());
            CommonFieldsDTO commonFields = sessionData.getCommonFields();

            StationDto stationDto = stations.get(paSendRTV2Request.getIdStation());

            gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory = new gov.telematici.pagamenti.ws.papernodo.ObjectFactory();

            sessionData.getAllRPTs().forEach(rpt -> {

                /*
                  Validate the station, checking if exists one with the required segregation code and, if is onboarded on GPD,
                  has the correct primitive version.
                  If it is not onboarded on GPD, it must be used for generate RT to sent to creditor institution via
                  institution's custom endpoint.
                 */
                if (CommonUtility.isStationOnboardedOnGpd(configCacheService, sessionData, noticeNumber, stationInGpdPartialPath)) {

                    generateREForNotGenerableRT(sessionData, cachedMapping.getIuv());

                } else {

                    Instant now = Instant.now();
                    PaymentServiceProviderDto psp = psps.get(rpt.getRpt().getPayeeInstitution().getSubjectUniqueIdentifier().getCode());

                    IntestazionePPT intestazionePPT = generateIntestazionePPT(
                            paSendRTV2Request.getReceipt().getFiscalCode(),
                            paSendRTV2Request.getReceipt().getCreditorReferenceId(),
                            rpt.getRpt().getTransferData().getCcp(),
                            commonFields.getCreditorInstitutionBrokerId(),
                            commonFields.getStationId());

                    JAXBElement<CtRicevutaTelematica> rt = new it.gov.digitpa.schemas._2011.pagamenti.ObjectFactory().createRT(generateCtRicevutaTelematica(rpt, paSendRTV2Request));

                    String paaInviaRTXmlString = generatePaaInviaRTAndTrace(intestazionePPT, jaxbElementUtil.objectToString(rt), objectFactory);

                    String url = CommonUtility.constructUrl(
                            stationDto.getConnection().getProtocol().getValue(),
                            stationDto.getConnection().getIp(),
                            stationDto.getConnection().getPort().intValue(),
                            stationDto.getService().getPath(),
                            null,
                            null
                    );

                    ReEventDto reEventDto = rtCosmosService.generateRE(
                            rptRequestEntity,
                            rpt,
                            paSendRTV2Request.getReceipt().getNoticeNumber(),
                            paSendRTV2Request.getReceipt().getCreditorReferenceId(),
                            stationDto.getStationCode(),
                            psp,
                            InternalStepStatus.GENERATED_NEW_RT);
                    reService.addRe(reEventDto);

                    send(url,
                            paaInviaRTXmlString,
                            rptRequestEntity,
                            rpt,
                            paSendRTV2Request.getReceipt().getNoticeNumber(),
                            paSendRTV2Request.getReceipt().getCreditorReferenceId(),
                            stationDto,
                            psp);
                }
            });
        } catch (AppException appEx) {
            throw appEx;
        } catch (Exception e) {
            throw new AppException(AppErrorCodeMessageEnum.GENERIC_ERROR, e.getMessage());
        }
    }

    private CtRicevutaTelematica generateCtRicevutaTelematica(RPTContentDTO rpt, Map<String, ConfigurationKeyDto> configurations, Instant now) {
        it.gov.digitpa.schemas._2011.pagamenti.ObjectFactory objectFactory = new it.gov.digitpa.schemas._2011.pagamenti.ObjectFactory();
        CtRicevutaTelematica ctRicevutaTelematica = objectFactory.createCtRicevutaTelematica();

        CtIstitutoAttestante ctIstitutoAttestante = objectFactory.createCtIstitutoAttestante();
        CtIdentificativoUnivoco ctIdentificativoUnivoco = objectFactory.createCtIdentificativoUnivoco();
        rtMapper.toCtIstitutoAttestante(ctIstitutoAttestante, ctIdentificativoUnivoco, configurations);

        CtDominio ctDominio = objectFactory.createCtDominio();
        rtMapper.toCtDominio(ctDominio, rpt.getRpt().getDomain());

        CtEnteBeneficiario ctEnteBeneficiario = objectFactory.createCtEnteBeneficiario();
        rtMapper.toCtEnteBeneficiario(ctEnteBeneficiario, rpt.getRpt().getPayeeInstitution());

        CtSoggettoPagatore ctSoggettoPagatore = objectFactory.createCtSoggettoPagatore();
        rtMapper.toCtSoggettoPagatore(ctSoggettoPagatore, rpt.getRpt().getPayer());

        CtDatiVersamentoRT ctDatiVersamentoRT = objectFactory.createCtDatiVersamentoRT();
        rtMapper.toCtDatiVersamentoRT(ctDatiVersamentoRT, rpt.getRpt().getTransferData(), now);

        rtMapper.toCtRicevutaTelematicaNegativa(ctRicevutaTelematica, rpt.getRpt(), now);

        ctRicevutaTelematica.setDominio(ctDominio);
        ctRicevutaTelematica.setIstitutoAttestante(ctIstitutoAttestante);
        ctRicevutaTelematica.setEnteBeneficiario(ctEnteBeneficiario);
        ctRicevutaTelematica.setSoggettoPagatore(ctSoggettoPagatore);
        ctRicevutaTelematica.setDatiPagamento(ctDatiVersamentoRT);

        return ctRicevutaTelematica;
    }

    private CtRicevutaTelematica generateCtRicevutaTelematica(RPTContentDTO rpt, PaSendRTV2Request paSendRTV2Request) {
        it.gov.digitpa.schemas._2011.pagamenti.ObjectFactory objectFactory = new it.gov.digitpa.schemas._2011.pagamenti.ObjectFactory();
        CtRicevutaTelematica ctRicevutaTelematica = objectFactory.createCtRicevutaTelematica();

        CtIstitutoAttestante ctIstitutoAttestante = objectFactory.createCtIstitutoAttestante();
        rtMapper.toCtIstitutoAttestante(ctIstitutoAttestante, paSendRTV2Request);

        CtDominio ctDominio = objectFactory.createCtDominio();
        rtMapper.toCtDominio(ctDominio, rpt.getRpt().getDomain());

        CtEnteBeneficiario ctEnteBeneficiario = objectFactory.createCtEnteBeneficiario();
        rtMapper.toCtEnteBeneficiario(ctEnteBeneficiario, rpt.getRpt().getPayeeInstitution());

        CtSoggettoPagatore ctSoggettoPagatore = objectFactory.createCtSoggettoPagatore();
        rtMapper.toCtSoggettoPagatore(ctSoggettoPagatore, paSendRTV2Request.getReceipt().getDebtor());

        rtMapper.toCtRicevutaTelematicaPositiva(ctRicevutaTelematica, rpt.getRpt(), paSendRTV2Request);

        CtDatiVersamentoRT ctDatiVersamentoRT = objectFactory.createCtDatiVersamentoRT();
        rtMapper.toCtDatiVersamentoRT(ctDatiVersamentoRT, rpt.getRpt().getTransferData(), paSendRTV2Request.getReceipt());

        ctRicevutaTelematica.setDominio(ctDominio);
        ctRicevutaTelematica.setIstitutoAttestante(ctIstitutoAttestante);
        ctRicevutaTelematica.setEnteBeneficiario(ctEnteBeneficiario);
        ctRicevutaTelematica.setSoggettoPagatore(ctSoggettoPagatore);
        ctRicevutaTelematica.setDatiPagamento(ctDatiVersamentoRT);

        return ctRicevutaTelematica;
    }

    private IntestazionePPT generateIntestazionePPT(String idDominio, String iuv, String ccp, String idIntermediarioPa, String idStazione) {
        gov.telematici.pagamenti.ws.nodoperpa.ppthead.ObjectFactory objectFactoryHead =
                new gov.telematici.pagamenti.ws.nodoperpa.ppthead.ObjectFactory();

        IntestazionePPT header = objectFactoryHead.createIntestazionePPT();
        header.setIdentificativoDominio(idDominio);
        header.setIdentificativoUnivocoVersamento(iuv);
        header.setCodiceContestoPagamento(ccp);
        header.setIdentificativoIntermediarioPA(idIntermediarioPa);
        header.setIdentificativoStazioneIntermediarioPA(idStazione);
        return header;
    }

    private RTRequestEntity generateRTEntity(String brokerPa, Instant now, String payload, String url) {
        try {
            return RTRequestEntity
                    .builder()
                    .id(brokerPa + "_" + UUID.randomUUID())
                    .primitive(PA_INVIA_RT)
                    .partitionKey(LocalDate.ofInstant(now, ZoneId.systemDefault()).toString())
                    .payload(AppBase64Util.base64Encode(ZipUtil.zip(payload)))
                    .url(url)
                    .retry(0)
                    .build();

        } catch (Exception e) {
            throw new AppException(AppErrorCodeMessageEnum.PARSING_GENERIC_ERROR);
        }
    }

    private String generatePaaInviaRTAndTrace(IntestazionePPT intestazionePPT,
                                              String xmlString,
                                              gov.telematici.pagamenti.ws.papernodo.ObjectFactory objectFactory) {
        PaaInviaRT paaInviaRT = objectFactory.createPaaInviaRT();
        paaInviaRT.setRt(AppBase64Util.base64Encode(xmlString.getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        JAXBElement<PaaInviaRT> paaInviaRTJaxb = objectFactory.createPaaInviaRT(paaInviaRT);

        SOAPMessage message = jaxbElementUtil.newMessage();
        jaxbElementUtil.addBody(message, paaInviaRTJaxb, PaaInviaRT.class);
        jaxbElementUtil.addHeader(message, intestazionePPT, IntestazionePPT.class);

        return jaxbElementUtil.toString(message);
    }

    private void send(String url, String payload,
                      RPTRequestEntity rptRequestEntity,
                      RPTContentDTO rptContentDTO,
                      String noticeNumber,
                      String paymentToken,
                      StationDto stationDto,
                      it.gov.pagopa.gen.wispconverter.client.cache.model.PaymentServiceProviderDto psp) {
        try {
            paaInviaRTService.send(url, payload);
        } catch (Exception e) {
            ReEventDto reEventDtoKo = rtCosmosService.generateRE(rptRequestEntity,
                    rptContentDTO,
                    noticeNumber,
                    paymentToken,
                    stationDto.getStationCode(),
                    psp,
                    InternalStepStatus.RT_SEND_FAILURE
            );
            reService.addRe(reEventDtoKo);

            RTRequestEntity rtRequestEntity = generateRTEntity(stationDto.getBrokerCode(), Instant.now(), payload, url);
            rtCosmosService.saveRTRequestEntity(rtRequestEntity);

            paaInviaRTServiceBusService.sendMessage(rtRequestEntity.getPartitionKey() + "_" + rtRequestEntity.getId());
        }
    }

    private void generateREForNotGenerableRT(SessionDataDTO sessionDataDTO, String iuv) {

        // creating event to be persisted for RE
        PaymentNoticeContentDTO paymentNotice = sessionDataDTO.getPaymentNoticeByIUV(iuv);
        generateRE(InternalStepStatus.NEGATIVE_RT_NOT_GENERABLE, iuv, paymentNotice.getNoticeNumber(), paymentNotice.getCcp(), null);
    }

    // TODO is this method useful here?
    private void generateREForGeneratedRT(SessionDataDTO sessionDataDTO, List<ReceiptDto> receipts) {

        // creating event to be persisted for RE
        //if (Boolean.TRUE.equals(isTracingOnREEnabled)) {
        for (ReceiptDto receipt : receipts) {
            String receiptInfo = "Receipt from: " + receipt.toString();
            PaymentNoticeContentDTO paymentNotice = sessionDataDTO.getPaymentNoticeByNoticeNumber(receipt.getNoticeNumber());
            generateRE(InternalStepStatus.NEGATIVE_RT_GENERATION_SUCCESS, paymentNotice.getIuv(), paymentNotice.getNoticeNumber(), paymentNotice.getCcp(), receiptInfo);
        }
        //}
    }

    private void generateRE(InternalStepStatus status, String iuv, String noticeNumber, String ccp, String otherInfo) {

        // setting data in MDC for next use
        ReEventDto reEvent = ReUtil.getREBuilder()
                .status(status)
                .iuv(iuv)
                .ccp(ccp)
                .noticeNumber(noticeNumber)
                .info(otherInfo)
                .build();
        reService.addRe(reEvent);
    }

}
