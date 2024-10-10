package it.gov.pagopa.wispconverter.service;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import it.gov.pagopa.wispconverter.controller.model.ReceiptTimerRequest;
import it.gov.pagopa.wispconverter.repository.CacheRepository;
import it.gov.pagopa.wispconverter.repository.model.enumz.InternalStepStatus;
import it.gov.pagopa.wispconverter.service.model.ReceiptDto;
import it.gov.pagopa.wispconverter.service.model.re.ReEventDto;
import it.gov.pagopa.wispconverter.util.Constants;
import it.gov.pagopa.wispconverter.util.LogUtils;
import it.gov.pagopa.wispconverter.util.MDCUtil;
import it.gov.pagopa.wispconverter.util.ReUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReceiptTimerService {

    public static final String CACHING_KEY_TEMPLATE = "wisp_timer_%s";

    private final CacheRepository cacheRepository;
    private final ReService reService;
    @Value("${azure.sb.wisp-payment-timeout-queue.connectionString}")
    private String connectionString;
    @Value("${azure.sb.queue.receiptTimer.name}")
    private String queueName;
    private ServiceBusSenderClient serviceBusSenderClient;

    @Autowired
    private ECommerceHangTimerService eCommerceHangTimerService;

    @PostConstruct
    public void post() {
        serviceBusSenderClient = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .sender()
                .queueName(queueName)
                .buildClient();
    }

    public void sendMessage(ReceiptTimerRequest message) {

        String paymentToken = message.getPaymentToken();
        String fiscalCode = message.getFiscalCode();
        String noticeNumber = message.getNoticeNumber();
        String sessionId = message.getSessionId();
        MDCUtil.setReceiptTimerInfoInMDC(fiscalCode, noticeNumber, paymentToken);

        // Duplicate Prevention Logic
        String sequenceNumberKey = String.format(CACHING_KEY_TEMPLATE, message.getPaymentToken());
        String value = cacheRepository.read(sequenceNumberKey, String.class);
        if (value != null) return; // already exists

        // Create Message with paaInviaRT- receipt generation info
        ReceiptDto receiptDto = ReceiptDto.builder()
                .paymentToken(paymentToken)
                .fiscalCode(fiscalCode)
                .noticeNumber(noticeNumber)
                .build();
        ServiceBusMessage serviceBusMessage = new ServiceBusMessage(receiptDto.toString());
        log.debug("Sending scheduled message {} to the queue: {}", message, queueName);

        // compute time and schedule message for consumer trigger
        OffsetDateTime scheduledExpirationTime = OffsetDateTime.now().plus(message.getExpirationTime(), ChronoUnit.MILLIS);
        Long sequenceNumber = serviceBusSenderClient.scheduleMessage(serviceBusMessage, scheduledExpirationTime);
        log.debug("Sent scheduled message_base64 {} to the queue: {}", LogUtils.encodeToBase64(message.toString()), queueName);
        generateRE(InternalStepStatus.RECEIPT_TIMER_GENERATION_CREATED_SCHEDULED_SEND, "Scheduled receipt: [" + message + "]");

        // insert {wisp_timer_<paymentToken>, sequenceNumber} for Duplicate Prevention Logic and for call cancelScheduledMessage(sequenceNumber)
        cacheRepository.insert(sequenceNumberKey, String.valueOf(sequenceNumber), message.getExpirationTime(), ChronoUnit.MILLIS);
        log.debug("Cache sequence number {} for payment-token: {}", sequenceNumber, sequenceNumberKey);
        generateRE(InternalStepStatus.RECEIPT_TIMER_GENERATION_CACHED_SEQUENCE_NUMBER, "Cached sequence number: [" + sequenceNumber + "] for payment token: [" + sequenceNumberKey + "]");

        // delete ecommerce hang timer: we delete the scheduled message from the queue
        eCommerceHangTimerService.cancelScheduledMessage(noticeNumber, fiscalCode, sessionId);
    }

    public void cancelScheduledMessage(List<String> paymentTokens) {
        paymentTokens.forEach(this::cancelScheduledMessage);
    }

    private void cancelScheduledMessage(String paymentToken) {

        MDCUtil.setReceiptTimerInfoInMDC(null, null, paymentToken);

        log.debug("Cancel scheduled message for payment-token {}", paymentToken);
        String sequenceNumberKey = String.format(CACHING_KEY_TEMPLATE, paymentToken);
        String sequenceNumberString = cacheRepository.read(sequenceNumberKey, String.class);

        // the message related to payment-token has either already been deleted or it does not exist:
        // without sequenceNumber is not possible to delete from serviceBus -> return
        if (sequenceNumberString != null) {

            // cancel scheduled message
            boolean deletedScheduledMessage = this.callCancelScheduledMessage(sequenceNumberString);
            boolean isDeleted = cacheRepository.delete(sequenceNumberKey);
            if (deletedScheduledMessage && isDeleted) {
                log.debug("Canceled scheduled message for payment-token_base64 {}", LogUtils.encodeToBase64(paymentToken));
                log.debug("Deleted sequence number {} for payment-token: {} from cache", sequenceNumberString, sequenceNumberKey);
                generateRE(InternalStepStatus.RECEIPT_TIMER_GENERATION_DELETED_SCHEDULED_SEND, "Deleted sequence number: [" + sequenceNumberString + "] for payment token: [" + sequenceNumberKey + "]");
            } else {
                generateRE(InternalStepStatus.RECEIPT_TIMER_GENERATION_SKIP_DELETE_SCHEDULED_SEND, "No element found in queue for sequence number: [" + sequenceNumberString + "]. Skipping delete for payment token [" + sequenceNumberKey + "]");
            }
        } else {
            generateRE(InternalStepStatus.RECEIPT_TIMER_GENERATION_SKIP_DELETE_SCHEDULED_SEND, "No element found in queue for payment token: [" + sequenceNumberKey + "]. The element was already deleted.");
        }
    }

    public boolean callCancelScheduledMessage(String sequenceNumberString) {
        boolean isOk;
        long sequenceNumber = Long.parseLong(sequenceNumberString);
        try {
            serviceBusSenderClient.cancelScheduledMessage(sequenceNumber);
            isOk = true;
        } catch (Exception exception) {
            log.debug(String.format("Scheduled message with sequence number [%s] not deleted. Cause: %s", sequenceNumberString, exception.getMessage()));
            isOk = false;
        }
        return isOk;
    }


    private void generateRE(InternalStepStatus status, String otherInfo) {
        // setting data in MDC for next use
        ReEventDto reEvent = ReUtil.getREBuilder()
                .status(status)
                .domainId(MDC.get(Constants.MDC_DOMAIN_ID))
                .noticeNumber(MDC.get(Constants.MDC_NOTICE_NUMBER))
                .paymentToken(MDC.get(Constants.MDC_PAYMENT_TOKEN))
                .info(otherInfo)
                .build();
        reService.addRe(reEvent);
    }
}
