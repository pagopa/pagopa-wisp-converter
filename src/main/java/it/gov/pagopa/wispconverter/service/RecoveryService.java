package it.gov.pagopa.wispconverter.service;

import it.gov.pagopa.gen.wispconverter.client.cache.model.ConnectionDto;
import it.gov.pagopa.gen.wispconverter.client.cache.model.StationDto;
import it.gov.pagopa.wispconverter.controller.ReceiptController;
import it.gov.pagopa.wispconverter.controller.model.RecoveryProxyReceiptRequest;
import it.gov.pagopa.wispconverter.controller.model.RecoveryProxyReceiptResponse;
import it.gov.pagopa.wispconverter.controller.model.RecoveryReceiptPaymentResponse;
import it.gov.pagopa.wispconverter.controller.model.RecoveryReceiptResponse;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.*;
import it.gov.pagopa.wispconverter.repository.model.RPTRequestEntity;
import it.gov.pagopa.wispconverter.repository.model.RTEntity;
import it.gov.pagopa.wispconverter.repository.model.RTRequestEntity;
import it.gov.pagopa.wispconverter.repository.model.ReEventEntity;
import it.gov.pagopa.wispconverter.repository.model.enumz.InternalStepStatus;
import it.gov.pagopa.wispconverter.service.model.ReceiptDto;
import it.gov.pagopa.wispconverter.service.model.re.ReEventDto;
import it.gov.pagopa.wispconverter.service.model.session.SessionDataDTO;
import it.gov.pagopa.wispconverter.util.CommonUtility;
import it.gov.pagopa.wispconverter.util.Constants;
import it.gov.pagopa.wispconverter.util.MDCUtil;
import it.gov.pagopa.wispconverter.util.ReUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
@RequiredArgsConstructor
public class RecoveryService {

    private static final String EVENT_TYPE_FOR_RECEIPTKO_SEARCH = "GENERATED_CACHE_ABOUT_RPT_FOR_RT_GENERATION";

    private static final String STATUS_RT_SEND_SUCCESS = "RT_SEND_SUCCESS";

    private static final String RECOVERY_VALID_START_DATE = "2024-09-03";

    private static final List<String> BUSINESS_PROCESSES = List.of("receipt-ok", "receipt-ko", "ecommerce-hang-timeout-trigger");
    private final ReceiptController receiptController;
    private final RPTRequestRepository rptRequestRepository;
    private final RTRepository rtRepository;
    private final RTRetryRepository rtRetryRepository;
    private final ReEventRepository reEventRepository;
    private final CacheRepository cacheRepository;
    private final ReService reService;
    private final RPTExtractorService rptExtractorService;
    private final ConfigCacheService configCacheService;
    private final ServiceBusService serviceBusService;
    @Value("${wisp-converter.apim.path}")
    private String apimPath;
    @Value("${wisp-converter.cached-requestid-mapping.ttl.minutes}")
    private Long requestIDMappingTTL;

    @Value("${wisp-converter.recovery.receipt-generation.wait-time.minutes:60}")
    private Long receiptGenerationWaitTime;


    public RecoveryReceiptResponse recoverReceiptKOForCreditorInstitution(String creditorInstitution, String dateFrom, String dateTo) {

        MDCUtil.setSessionDataInfo("recovery-receipt-ko");
        LocalDate lowerLimit = LocalDate.parse(RECOVERY_VALID_START_DATE, DateTimeFormatter.ISO_LOCAL_DATE);
        if (LocalDate.parse(dateFrom, DateTimeFormatter.ISO_LOCAL_DATE).isBefore(lowerLimit)) {
            throw new AppException(AppErrorCodeMessageEnum.ERROR, String.format("The lower bound cannot be lower than [%s]", RECOVERY_VALID_START_DATE));
        }

        LocalDate now = LocalDate.now();
        LocalDate parse = LocalDate.parse(dateTo, DateTimeFormatter.ISO_LOCAL_DATE);
        if (parse.isAfter(now)) {
            throw new AppException(AppErrorCodeMessageEnum.ERROR, String.format("The upper bound cannot be higher than [%s]", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))));
        }

        String dateToRefactored;
        if (now.isEqual(parse)) {
            ZonedDateTime nowMinusMinutes = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(receiptGenerationWaitTime);
            dateToRefactored = nowMinusMinutes.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            log.info("Upper bound forced to {}", dateToRefactored);
        } else {
            dateToRefactored = dateTo + " 23:59:59";
            log.info("Upper bound set to {}", dateToRefactored);
        }

        List<RTEntity> receiptRTs = rtRepository.findByOrganizationId(creditorInstitution, dateFrom, dateToRefactored);
        List<RecoveryReceiptPaymentResponse> paymentsToReconcile = receiptRTs.stream().map(entity -> RecoveryReceiptPaymentResponse.builder()
                        .iuv(entity.getIuv())
                        .ccp(entity.getCcp())
                        .build())
                .toList();

        CompletableFuture<Boolean> executeRecovery = recoverReceiptKOAsync(dateFrom, dateTo, creditorInstitution, paymentsToReconcile);
        executeRecovery
                .thenAccept(value -> log.debug("Reconciliation for creditor institution [{}] in date range [{}-{}] completed!", creditorInstitution, dateFrom, dateTo))
                .exceptionally(e -> {
                    log.error("Reconciliation for creditor institution [{}] in date range [{}-{}] ended unsuccessfully!", creditorInstitution, dateFrom, dateTo, e);
                    throw new AppException(e, AppErrorCodeMessageEnum.ERROR, e.getMessage());
                });

        return RecoveryReceiptResponse.builder()
                .payments(paymentsToReconcile)
                .build();
    }

    private CompletableFuture<Boolean> recoverReceiptKOAsync(String dateFrom, String dateTo, String creditorInstitution, List<RecoveryReceiptPaymentResponse> paymentsToReconcile) {

        return CompletableFuture.supplyAsync(() -> {

            for (RecoveryReceiptPaymentResponse payment : paymentsToReconcile) {

                String iuv = payment.getIuv();
                String ccp = payment.getCcp();

                try {
                    List<ReEventEntity> reEvents = reEventRepository.findByIuvAndOrganizationId(dateFrom, dateTo, iuv, creditorInstitution);

                    List<ReEventEntity> filteredEvents = reEvents.stream()
                            .filter(event -> EVENT_TYPE_FOR_RECEIPTKO_SEARCH.equals(event.getStatus()))
                            .filter(event -> ccp.equals(event.getCcp()))
                            .sorted(Comparator.comparing(ReEventEntity::getInsertedTimestamp))
                            .toList();

                    int numberOfEvents = filteredEvents.size();
                    if (numberOfEvents > 0) {

                        ReEventEntity event = filteredEvents.get(numberOfEvents - 1);
                        String noticeNumber = event.getNoticeNumber();
                        String sessionId = event.getSessionId();

                        // search by sessionId, then filter by status=RT_SEND_SUCCESS. If there is zero, then proceed
                        List<ReEventEntity> reEventsRT = reEventRepository.findBySessionIdAndStatus(dateFrom, dateTo, sessionId, STATUS_RT_SEND_SUCCESS);

                        if (reEventsRT.isEmpty()) {
                            String navToIuvMapping = String.format(DecouplerService.MAP_CACHING_KEY_TEMPLATE, creditorInstitution, noticeNumber);
                            String iuvToSessionIdMapping = String.format(DecouplerService.CACHING_KEY_TEMPLATE, creditorInstitution, iuv);
                            this.cacheRepository.insert(navToIuvMapping, iuvToSessionIdMapping, this.requestIDMappingTTL);
                            this.cacheRepository.insert(iuvToSessionIdMapping, sessionId, this.requestIDMappingTTL);

                            MDC.put(Constants.MDC_BUSINESS_PROCESS, "receipt-ko");
                            generateRE(Constants.PAA_INVIA_RT, null, InternalStepStatus.RT_START_RECONCILIATION_PROCESS, creditorInstitution, iuv, noticeNumber, ccp, sessionId);
                            String receiptKoRequest = ReceiptDto.builder()
                                    .fiscalCode(creditorInstitution)
                                    .noticeNumber(noticeNumber)
                                    .build()
                                    .toString();
                            this.receiptController.receiptKo(receiptKoRequest);
                            generateRE(Constants.PAA_INVIA_RT, "Success", InternalStepStatus.RT_END_RECONCILIATION_PROCESS, creditorInstitution, iuv, noticeNumber, ccp, sessionId);
                            MDC.remove(Constants.MDC_BUSINESS_PROCESS);
                        }
                    }

                } catch (Exception e) {
                    generateRE(Constants.PAA_INVIA_RT, "Failure", InternalStepStatus.RT_END_RECONCILIATION_PROCESS, creditorInstitution, iuv, null, ccp, null);
                    throw new AppException(e, AppErrorCodeMessageEnum.ERROR, e.getMessage());
                }
            }

            return true;
        });
    }

    private void generateRE(String primitive, String operationStatus, InternalStepStatus status, String domainId, String iuv, String noticeNumber, String ccp, String sessionId) {

        // setting data in MDC for next use
        ReEventDto reEvent = ReUtil.getREBuilder()
                .primitive(primitive)
                .operationStatus(operationStatus)
                .status(status)
                .sessionId(sessionId)
                .domainId(domainId)
                .iuv(iuv)
                .ccp(ccp)
                .noticeNumber(noticeNumber)
                .build();
        reService.addRe(reEvent);
    }

    public RecoveryProxyReceiptResponse recoverReceiptToBeSentByProxy(RecoveryProxyReceiptRequest request) {

        RecoveryProxyReceiptResponse response = RecoveryProxyReceiptResponse.builder()
                .receiptStatus(new LinkedList<>())
                .build();

        MDCUtil.setSessionDataInfo("recovery-receipt-without-proxy");
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
                if (idempotencyKeyComponents.length != 2) {
                    throw new AppException(AppErrorCodeMessageEnum.ERROR, String.format("Invalid idempotency key [%s]. It must be composed of sessionId and notice number.", idempotencyKey));
                }

                sessionId = idempotencyKeyComponents[0];
                Optional<RPTRequestEntity> rptRequestOpt = rptRequestRepository.findById(sessionId);
                if (rptRequestOpt.isEmpty()) {
                    throw new AppException(AppErrorCodeMessageEnum.ERROR, String.format("No valid RPT request found with id [%s].", sessionId));
                }

                RPTRequestEntity rptRequestEntity = rptRequestOpt.get();
                SessionDataDTO sessionData = rptExtractorService.extractSessionData(rptRequestEntity.getPrimitive(), rptRequestEntity.getPayload());

                String stationId = sessionData.getCommonFields().getStationId();
                StationDto station = configCacheService.getStationByIdFromCache(stationId);

                ConnectionDto stationConnection = station.getConnection();
                String url = CommonUtility.constructUrl(
                        stationConnection.getProtocol().getValue(),
                        stationConnection.getIp(),
                        stationConnection.getPort().intValue(),
                        station.getService() != null ? station.getService().getPath() : "",
                        null,
                        null
                );
                InetSocketAddress proxyAddress = CommonUtility.constructProxyAddress(url, station, apimPath);
                if (proxyAddress != null) {
                    rtRequestEntity.setProxyAddress(String.format("%s:%s", proxyAddress.getHostString(), proxyAddress.getPort()));
                    rtRetryRepository.save(rtRequestEntity);
                }

                String compositedIdForReceipt = String.format("%s_%s", rtRequestEntity.getPartitionKey(), rtRequestEntity.getId());
                serviceBusService.sendMessage(compositedIdForReceipt, null);
                generateRE(null, "Success", InternalStepStatus.RT_SEND_RESCHEDULING_SUCCESS, null, null, null, null, sessionId);
                response.getReceiptStatus().add(Pair.of(receiptId, "SUCCESS"));

            } catch (Exception e) {

                log.error("Reconciliation for receipt id [{}] ended unsuccessfully!", receiptId, e);
                generateRE(Constants.PAA_INVIA_RT, "Failure", InternalStepStatus.RT_SEND_RESCHEDULING_FAILURE, null, null, null, null, sessionId);
                response.getReceiptStatus().add(Pair.of(receiptId, String.format("FAILURE: [%s]", e.getMessage())));
            }
        }

        return response;
    }
}
