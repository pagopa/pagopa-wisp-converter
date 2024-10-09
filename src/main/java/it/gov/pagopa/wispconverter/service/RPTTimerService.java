package it.gov.pagopa.wispconverter.service;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import it.gov.pagopa.wispconverter.controller.model.RPTTimerRequest;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.CacheRepository;
import it.gov.pagopa.wispconverter.repository.RPTRequestRepository;
import it.gov.pagopa.wispconverter.repository.model.enumz.InternalStepStatus;
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
import static it.gov.pagopa.wispconverter.util.MDCUtil.setRPTTimerInfoInMDC;

@Service
@Slf4j
@RequiredArgsConstructor
public class RPTTimerService {

    public static final String RPT_TIMER_MESSAGE_KEY_FORMAT = "wisp_timer_rpt_%s";

    @Value("${azure.sb.wisp-rpt-timeout-queue.connectionString}")
    private String connectionString;

    @Value("${azure.sb.queue.wisp-rpt-timeout.name}")
    private String queueName;

    @Value("${wisp-converter.wisp-rpt.timeout.seconds}")
    private Integer expirationTime;

    @Value("${disable-service-bus}")
    private boolean disableServiceBus;

    private ReService reService;

    private ServiceBusSenderClient serviceBusSenderClient;

    private CacheRepository cacheRepository;

    private RPTRequestRepository rptRequestRepository;

    @Autowired
    public RPTTimerService(CacheRepository cacheRepository, ServiceBusSenderClient serviceBusSenderClient, ReService reService, RPTRequestRepository rptRequestRepository) {
        this.cacheRepository = cacheRepository;
        this.serviceBusSenderClient = serviceBusSenderClient;
        this.reService = reService;
        this.rptRequestRepository = rptRequestRepository;
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
    public void sendMessage(RPTTimerRequest message) {
        if (!disableServiceBus) {
            String sessionId = message.getSessionId();
            setRPTTimerInfoInMDC(sessionId);

            // checking if sessionId is present in container data
            this.rptRequestRepository.findById(sessionId).orElseThrow(() -> new AppException(AppErrorCodeMessageEnum.PERSISTENCE_RPT_NOT_FOUND, sessionId));

            String key = String.format(RPT_TIMER_MESSAGE_KEY_FORMAT, sessionId);

            // If the key is already present in the cache, we delete it to avoid duplicated message.
            if (Boolean.TRUE.equals(cacheRepository.hasKey(key))) {
                cancelScheduledMessage(sessionId);
            }

            // build the service bus message
            ServiceBusMessage serviceBusMessage = new ServiceBusMessage(message.toString());
            log.debug("Sending scheduled message {} to the queue: {}", sanitizeInput(message.toString()), queueName);

            // compute time and schedule message for consumer trigger
            OffsetDateTime scheduledExpirationTime = OffsetDateTime.now().plusSeconds(expirationTime);
            Long sequenceNumber = serviceBusSenderClient.scheduleMessage(serviceBusMessage, scheduledExpirationTime);

            try {
                // insert in Redis cache sequenceNumber of the message
                cacheRepository.insert(key, sequenceNumber.toString(), expirationTime, ChronoUnit.SECONDS);

                // log event
                log.info("Sent scheduled message_base64 {} to the queue: {}", LogUtils.encodeToBase64(sanitizeInput(message.toString())), queueName);
                generateRE(InternalStepStatus.RPT_TIMER_CREATED, "Scheduled RPTTimerService: [" + message + "]");
            } catch (Exception e) {
                serviceBusSenderClient.cancelScheduledMessage(sequenceNumber);

                // log event
                log.debug("Timer not set due to an exception for rpt_timer_key: {} and sessionId: {}", sanitizeInput(key), sanitizeInput(sessionId));
                generateRE(InternalStepStatus.RPT_TIMER_NOT_SET, "Exception timer not set: [" + sequenceNumber + "] for sessionId: [" + sessionId + "]");
            }
        }
    }

    /**
     * This method deletes a scheduled message from the queue
     *
     * @param sessionId use to find the message
     */
    public void cancelScheduledMessage(String sessionId) {
        if (!disableServiceBus) {
            log.debug("Cancel scheduled message for RPTTimer {}", sanitizeInput(sessionId));
            String key = String.format(RPT_TIMER_MESSAGE_KEY_FORMAT, sessionId);

            // get the sequenceNumber from the Redis cache
            String sequenceNumber = cacheRepository.read(key, String.class);

            if (sequenceNumber != null) {

                // cancel scheduled message in the service bus queue
                callCancelScheduledMessage(sequenceNumber);
                log.info("Canceled scheduled message for rpt_timer_base64 {}", LogUtils.encodeToBase64(sanitizeInput(sessionId)));

                // delete the sequenceNumber from the Redis cache
                cacheRepository.delete(key);

                // log event
                log.debug("Deleted sequence number {} for rpt_timer_base64-token: {} from cache", sequenceNumber, sanitizeInput(sessionId));
                generateRE(InternalStepStatus.RPT_TIMER_DELETED, "Deleted sequence number: [" + sequenceNumber + "] for sessionId: [" + sessionId + "]");
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
