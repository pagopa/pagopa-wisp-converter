package it.gov.pagopa.wispconverter.service;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.CacheRepository;
import it.gov.pagopa.wispconverter.repository.model.enumz.InternalStepStatus;
import it.gov.pagopa.wispconverter.service.model.ECommerceHangTimeoutMessage;
import it.gov.pagopa.wispconverter.service.model.re.ReEventDto;
import it.gov.pagopa.wispconverter.util.Constants;
import it.gov.pagopa.wispconverter.util.LogUtils;
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

import static it.gov.pagopa.wispconverter.util.CommonUtility.sanitizeInput;
import static it.gov.pagopa.wispconverter.util.MDCUtil.setEcommerceHangTimerInfoInMDC;

@Service
@Slf4j
@RequiredArgsConstructor
public class ECommerceHangTimerService {

    public static final String ECOMMERCE_TIMER_MESSAGE_KEY_FORMAT = "wisp_timer_hang_%s_%s_%s";

    public static final String OLD_ECOMMERCE_TIMER_MESSAGE_KEY_FORMAT = "wisp_timer_hang_%s_%s";

    @Value("${azure.sb.wisp-ecommerce-hang-timeout-queue.connectionString}")
    private String connectionString;

    @Value("${azure.sb.queue.ecommerce-hang-timeout.name}")
    private String queueName;

    @Value("${wisp-converter.ecommerce-hang.timeout.seconds}")
    private Integer expirationTime;

    @Value("${disable-service-bus}")
    private boolean disableServiceBus;

    private ReService reService;

    private ServiceBusSenderClient serviceBusSenderClient;

    private CacheRepository cacheRepository;

    @Autowired
    public ECommerceHangTimerService(CacheRepository cacheRepository, ServiceBusSenderClient serviceBusSenderClient, ReService reService) {
        this.cacheRepository = cacheRepository;
        this.serviceBusSenderClient = serviceBusSenderClient;
        this.reService = reService;
    }

    @PostConstruct
    public void post() {
        serviceBusSenderClient = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .sender()
                .queueName(queueName)
                .buildClient();
    }

    /**
     * This method add a new scheduled message in the queue.
     * Note: This method use Redis Cache to handle the metadata of the message
     *
     * @param message the message to send on the queue of the service bus.
     */
    public void sendMessage(ECommerceHangTimeoutMessage message) {
        if(!disableServiceBus) {
            String noticeNumber = message.getNoticeNumber();
            String fiscalCode = message.getFiscalCode();
            String sessionId = message.getSessionId();
            setEcommerceHangTimerInfoInMDC(fiscalCode, noticeNumber);

            String key = String.format(ECOMMERCE_TIMER_MESSAGE_KEY_FORMAT, noticeNumber, fiscalCode, sessionId);

            // If the key is already present in the cache, we delete it to avoid duplicated message.
            if (Boolean.TRUE.equals(cacheRepository.hasKey(key))) {
                cancelScheduledMessage(noticeNumber, fiscalCode, sessionId);
            }

            // build the service bus message
            ServiceBusMessage serviceBusMessage = new ServiceBusMessage(message.toString());
            log.debug("Sending scheduled message {} to the queue: {}", message, queueName);

            // compute time and schedule message for consumer trigger
            OffsetDateTime scheduledExpirationTime = OffsetDateTime.now().plusSeconds(expirationTime);
            Long sequenceNumber = serviceBusSenderClient.scheduleMessage(serviceBusMessage, scheduledExpirationTime);

            // log event
            log.debug("Sent scheduled message_base64 {} to the queue: {}", LogUtils.encodeToBase64(message.toString()), queueName);
            generateRE(InternalStepStatus.ECOMMERCE_HANG_TIMER_CREATED, "Scheduled eCommerce hang release: [" + message + "]");

            // insert in Redis cache sequenceNumber of the message
            cacheRepository.insert(key, sequenceNumber.toString(), expirationTime, ChronoUnit.SECONDS);
        }
    }


    /**
     * This method deletes a scheduled message from the queue
     *
     * @param noticeNumber use to find the message
     * @param fiscalCode   use to find the message
     */
    public void cancelScheduledMessage(String noticeNumber, String fiscalCode, String sessionId) {
        if(!disableServiceBus) {
            log.debug("Cancel scheduled message for eCommerce hang release {} {}", sanitizeInput(noticeNumber), sanitizeInput(fiscalCode));
            String key = String.format(ECOMMERCE_TIMER_MESSAGE_KEY_FORMAT, noticeNumber, fiscalCode, sessionId);

            // get the sequenceNumber from the Redis cache
            String sequenceNumber = cacheRepository.read(key, String.class);

            // for retro-compatibility check old key format wisp_timer_hang_<notice-number>_<fiscal-code>
            if (sequenceNumber == null) {
                String oldKey = String.format(OLD_ECOMMERCE_TIMER_MESSAGE_KEY_FORMAT, noticeNumber, fiscalCode);
                sequenceNumber = cacheRepository.read(oldKey, String.class);
            }

            if (sequenceNumber != null) {

                // cancel scheduled message in the service bus queue
                callCancelScheduledMessage(sequenceNumber);
                log.debug("Canceled scheduled message for ecommerce_hang_timeout_base64 {} {}", LogUtils.encodeToBase64(sanitizeInput(noticeNumber)), LogUtils.encodeToBase64(sanitizeInput(fiscalCode)));

                // delete the sequenceNumber from the Redis cache
                cacheRepository.delete(key);

                // log event
                log.debug("Deleted sequence number {} for ecommerce_hang_timeout_base64-token: {} {} from cache", sequenceNumber, sanitizeInput(noticeNumber), sanitizeInput(fiscalCode));
                generateRE(InternalStepStatus.ECOMMERCE_HANG_TIMER_DELETED, "Deleted sequence number: [" + sequenceNumber + "] for notice: [" + noticeNumber + "] for fiscalCode [" + fiscalCode + "]");
            }
        }
    }

    private void callCancelScheduledMessage(String sequenceNumberString) {
        if (!disableServiceBus) {
            long sequenceNumber = Long.parseLong(sequenceNumberString);
            try {
                // delete the message from the queue
                serviceBusSenderClient.cancelScheduledMessage(sequenceNumber);
            } catch (Exception exception) {
                throw new AppException(AppErrorCodeMessageEnum.PERSISTENCE_SERVICE_BUS_CANCEL_ERROR, exception.getMessage());
            }
        }
    }


    private void generateRE(InternalStepStatus status, String otherInfo) {
        // setting data in MDC for next use
        // TODO fix the info
        ReEventDto reEvent = ReUtil.getREBuilder()
                .status(status)
                .domainId(MDC.get(Constants.MDC_DOMAIN_ID))
                .paymentToken(MDC.get(Constants.MDC_PAYMENT_TOKEN))
                .info(otherInfo)
                .build();
        reService.addRe(reEvent);
    }

}
