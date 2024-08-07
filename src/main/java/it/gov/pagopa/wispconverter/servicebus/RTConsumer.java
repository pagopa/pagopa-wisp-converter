package it.gov.pagopa.wispconverter.servicebus;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import gov.telematici.pagamenti.ws.nodoperpa.ppthead.IntestazionePPT;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.model.RTRequestEntity;
import it.gov.pagopa.wispconverter.repository.model.enumz.IdempotencyStatusEnum;
import it.gov.pagopa.wispconverter.repository.model.enumz.InternalStepStatus;
import it.gov.pagopa.wispconverter.repository.model.enumz.ReceiptTypeEnum;
import it.gov.pagopa.wispconverter.service.IdempotencyService;
import it.gov.pagopa.wispconverter.service.PaaInviaRTSenderService;
import it.gov.pagopa.wispconverter.service.RtRetryComosService;
import it.gov.pagopa.wispconverter.service.ServiceBusService;
import it.gov.pagopa.wispconverter.util.*;
import jakarta.xml.soap.SOAPMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;

@Component
@Slf4j
public class RTConsumer extends SBConsumer {
    @Value("${wisp-converter.rt-send.max-retries:48}")
    private Integer maxRetries;

    @Value("${wisp-converter.rt-send.scheduling-time-in-minutes:60}")
    private Integer schedulingTimeInMinutes;

    @Value("${azure.sb.wisp-paainviart-queue.connectionString}")
    private String connectionString;

    @Value("${azure.sb.paaInviaRT.name}")
    private String queueName;

    @Autowired
    private RtRetryComosService rtRetryComosService;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private PaaInviaRTSenderService paaInviaRTSenderService;

    @Autowired
    private ServiceBusService serviceBusService;

    @Autowired
    private JaxbElementUtil jaxbElementUtil;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeClient() {
        if (receiverClient != null) {
            log.info("[Scheduled] Starting RTConsumer {}", ZonedDateTime.now());
            receiverClient.start();
        }
    }

    @PostConstruct
    public void post() {
        if (StringUtils.isNotBlank(connectionString) && !connectionString.equals("-")) {
            receiverClient = CommonUtility
                    .getServiceBusProcessorClient(
                            connectionString, queueName, this::processMessage, this::processError
                    );
        }
    }

    public void processMessage(ServiceBusReceivedMessageContext context) {

        // retrieving content from context of arrived message
        setSessionDataInfoInMDC("resend-rt");
        ServiceBusReceivedMessage message = context.getMessage();
        log.info("Processing " + message.getMessageId());

        // extracting the values needed for the search of the receipt persisted in storage
        String compositedIdForReceipt = new String(message.getBody().toBytes());
        String[] idSections = compositedIdForReceipt.split("_");
        String rtInsertionDate = idSections[0];
        String receiptId = idSections[1] + "_" + idSections[2];

        // get RT request entity from database
        MDC.put(Constants.MDC_SESSION_ID, receiptId);
        RTRequestEntity rtRequestEntity = rtRetryComosService.getRTRequestEntity(receiptId, rtInsertionDate);
        String idempotencyKey = rtRequestEntity.getIdempotencyKey();
        ReceiptTypeEnum receiptType = rtRequestEntity.getReceiptType();

        IdempotencyStatusEnum idempotencyStatus = IdempotencyStatusEnum.FAILED;
        boolean isIdempotencyKeyProcessable = false;
        try {

            // before sending the RT to the creditor institution, the idempotency key must be checked in order to not send duplicated receipts
            isIdempotencyKeyProcessable = idempotencyService.isIdempotencyKeyProcessable(idempotencyKey, receiptType);
            if (isIdempotencyKeyProcessable) {

                // Lock idempotency key status to avoid concurrency issues
                idempotencyService.lockIdempotencyKey(idempotencyKey, receiptType);

                // If receipt was found, it must be sent to creditor institution, so it try this operation
                log.debug("Sending message {}, retry: {}", compositedIdForReceipt, rtRequestEntity.getRetry());
                resendRTToCreditorInstitution(receiptId, rtRequestEntity, compositedIdForReceipt, idempotencyKey);

                idempotencyStatus = IdempotencyStatusEnum.SUCCESS;

            } else {

                // Status was locked due to concurrent execution, so it will be retried at the next execution (but only if it is not completed)
                if (!idempotencyService.isCompleted(idempotencyKey)) {
                    reScheduleReceiptSend(rtRequestEntity, receiptId, compositedIdForReceipt);
                }
            }

        } catch (Exception e) {

            // Generate a new event in RE for store the unsuccessful re-sending of the receipt
            generateREForNotSentRT(e);

        }

        // Unlock idempotency key after a successful operation
        if (isIdempotencyKeyProcessable) {
            try {
                idempotencyService.unlockIdempotencyKey(idempotencyKey, receiptType, idempotencyStatus);
            } catch (AppException e) {
                log.error("AppException: ", e);
            }
        }
    }

    private void resendRTToCreditorInstitution(String receiptId, RTRequestEntity receipt, String compositedIdForReceipt, String idempotencyKey) {

        try {

            log.info("Sending receipt [{}]", receiptId);

            // unzip retrieved zipped payload from GZip format
            byte[] unzippedPayload = ZipUtil.unzip(AppBase64Util.base64Decode(receipt.getPayload()));
            SOAPMessage envelopeElement = jaxbElementUtil.getMessage(unzippedPayload);
            IntestazionePPT header = jaxbElementUtil.getHeader(envelopeElement, IntestazionePPT.class);

            // set MDC session data for RE
            String[] idempotencyKeySections = idempotencyKey.split("_");
            MDCUtil.setSessionDataInfoInMDC(header, idempotencyKeySections[2]);

            String rawPayload = new String(unzippedPayload);
            paaInviaRTSenderService.sendToCreditorInstitution(receipt.getUrl(), extractHeaders(receipt.getHeaders()), rawPayload);
            rtRetryComosService.deleteRTRequestEntity(receipt);
            log.info("Sent receipt [{}]", receiptId);

            // generate a new event in RE for store the successful re-sending of the receipt
            generateREForSentRT();

        } catch (AppException e) {

            // generate a new event in RE for store the unsuccessful re-sending of the receipt
            generateREForNotSentRT(e);

            // Rescheduled due to errors caused by faulty communication with creditor institution
            reScheduleReceiptSend(receipt, receiptId, compositedIdForReceipt);

        } catch (IOException e) {

            throw new AppException(AppErrorCodeMessageEnum.PARSING_INVALID_ZIPPED_PAYLOAD);
        }
    }

    private List<Pair<String, String>> extractHeaders(List<String> headers) {
        List<Pair<String, String>> headerPairs = new LinkedList<>();
        for (String rawHeader : headers) {
            String[] keys = rawHeader.split(":");
            if (keys.length == 2) {
                headerPairs.add(Pair.of(keys[0], keys[1]));
            }
        }
        return headerPairs;
    }

    private void reScheduleReceiptSend(RTRequestEntity receipt, String receiptId, String compositedIdForReceipt) {

        // because of the not sent receipt, it is necessary to schedule a retry of the sending process for this receipt
        if (receipt.getRetry() < this.maxRetries - 1) {

            try {

                // if required, update the retry count for the retrieved RT
                log.debug("Increasing retry by one and saving receipt with id: [{}]", receiptId);
                receipt.setRetry(receipt.getRetry() + 1);
                rtRetryComosService.saveRTRequestEntity(receipt);

                // because of the not sent receipt, it is necessary to schedule a retry of the sending process for this receipt
                serviceBusService.sendMessage(compositedIdForReceipt, schedulingTimeInMinutes);

                // generate a new event in RE for store the successful scheduling of the RT send
                generateREForSuccessfulReschedulingSentRT();

            } catch (Exception e) {

                // generate a new event in RE for store the unsuccessful scheduling of the RT send
                generateREForFailedReschedulingSentRT(e);
            }
        } else {

            // generate a new event in RE for store the unsuccessful scheduling of the RT send
            generateREForMaxRetriesOnReschedulingSentRT(receipt.getRetry());
        }
    }

    private void generateREForSentRT() {

        generateRE(InternalStepStatus.RT_SCHEDULED_SEND_SUCCESS, "Re-scheduled send operation: success.");
    }

    private void generateREForNotSentRT(Throwable e) {

        generateRE(InternalStepStatus.RT_SCHEDULED_SEND_FAILURE, "Re-scheduled send operation: failure. Caused by: " + e.getMessage());
    }

    private void generateREForSuccessfulReschedulingSentRT() {

        generateRE(InternalStepStatus.RT_SEND_RESCHEDULING_SUCCESS, "Trying to re-schedule for next retry: success.");
    }

    private void generateREForFailedReschedulingSentRT(Throwable exception) {

        generateRE(InternalStepStatus.RT_SEND_RESCHEDULING_FAILURE, "Trying to re-schedule for next retry: failure. Caused by: " + exception.getMessage());
    }

    private void generateREForMaxRetriesOnReschedulingSentRT(int retries) {

        generateRE(InternalStepStatus.RT_SEND_RESCHEDULING_REACHED_MAX_RETRIES, "Reached max retries: [" + retries + "].");
    }

}
