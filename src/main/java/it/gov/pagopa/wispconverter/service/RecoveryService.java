package it.gov.pagopa.wispconverter.service;

import it.gov.pagopa.wispconverter.controller.ReceiptController;
import it.gov.pagopa.wispconverter.controller.model.RecoveryReceiptPaymentResponse;
import it.gov.pagopa.wispconverter.controller.model.RecoveryReceiptResponse;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.CacheRepository;
import it.gov.pagopa.wispconverter.repository.RTRepository;
import it.gov.pagopa.wispconverter.repository.ReEventRepository;
import it.gov.pagopa.wispconverter.repository.model.RTEntity;
import it.gov.pagopa.wispconverter.repository.model.ReEventEntity;
import it.gov.pagopa.wispconverter.repository.model.enumz.InternalStepStatus;
import it.gov.pagopa.wispconverter.service.model.ReceiptDto;
import it.gov.pagopa.wispconverter.service.model.re.ReEventDto;
import it.gov.pagopa.wispconverter.util.Constants;
import it.gov.pagopa.wispconverter.util.ReUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
@RequiredArgsConstructor
public class RecoveryService {

    private static final String EVENT_TYPE_FOR_RECEIPTKO_SEARCH = "GENERATED_CACHE_ABOUT_RPT_FOR_RT_GENERATION";

    private static final String STATUS_RT_SEND_SUCCESS = "RT_SEND_SUCCESS";

    private static final List<String> BUSINESS_PROCESSES = List.of("receipt-ok", "receipt-ko");

    private final ReceiptController receiptController;

    private final RTRepository rtRepository;

    private final ReEventRepository reEventRepository;

    private final CacheRepository cacheRepository;

    private final ReService reService;

    @Value("${wisp-converter.cached-requestid-mapping.ttl.minutes}")
    private Long requestIDMappingTTL;

    @Value("${wisp-converter.recovery.receipt-generation.wait-time.minutes:60}")
    private Long receiptGenerationWaitTime;

    public RecoveryReceiptResponse recoverReceiptKOForCreditorInstitution(String creditorInstitution, String dateFrom, String dateTo) {

        String startDate = "2024-09-03";
        LocalDate lowerLimit = LocalDate.parse(startDate, DateTimeFormatter.ISO_LOCAL_DATE);
        if (LocalDate.parse(dateFrom, DateTimeFormatter.ISO_LOCAL_DATE).isBefore(lowerLimit)) {
            throw new AppException(AppErrorCodeMessageEnum.ERROR, String.format("The lower bound cannot be lower than [%s]", startDate));
        }

        LocalDate now = LocalDate.now();
        LocalDate parse = LocalDate.parse(dateTo, DateTimeFormatter.ISO_LOCAL_DATE);
        if (parse.isAfter(now)) {
            throw new AppException(AppErrorCodeMessageEnum.ERROR, String.format("The upper bound cannot be higher than [%s]", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))));
        }

        String dateToRefactored = dateTo;
        if (now.isEqual(parse)) {
            ZonedDateTime nowMinus1h = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(receiptGenerationWaitTime);
            dateToRefactored = nowMinus1h.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            log.info("Upper bound forced to {}", dateToRefactored);
        }


        List<RTEntity> receiptRTs = rtRepository.findByOrganizationId(creditorInstitution, dateFrom, dateToRefactored);
        List<RecoveryReceiptPaymentResponse> paymentsToReconcile = receiptRTs.stream().map(entity -> RecoveryReceiptPaymentResponse.builder()
                        .iuv(entity.getIuv())
                        .ccp(entity.getCcp())
                        .build())
                .toList();

        CompletableFuture<Boolean> executeRecovery = recoverReceiptKOAsync(dateFrom, dateTo, creditorInstitution, paymentsToReconcile);
        executeRecovery
                .thenAccept(value -> log.info("Reconciliation for creditor institution [{}] in date range [{}-{}] completed!", creditorInstitution, dateFrom, dateTo))
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

                        // search by sessionId, then filter by status=RT_SEND_SUCCESS and businessProcess=receipt-ko|ok. If there is zero, then proceed
                        List<ReEventEntity> reEventsRT = reEventRepository.findBySessionIdAndStatusAndBusinessProcess(
                                dateFrom, dateTo, sessionId, STATUS_RT_SEND_SUCCESS, BUSINESS_PROCESSES
                        );

                        if(reEventsRT.isEmpty()) {
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
}
