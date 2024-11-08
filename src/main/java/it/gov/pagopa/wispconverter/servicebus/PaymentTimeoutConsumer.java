package it.gov.pagopa.wispconverter.servicebus;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.wispconverter.repository.model.enumz.WorkflowStatus;
import it.gov.pagopa.wispconverter.service.ReService;
import it.gov.pagopa.wispconverter.service.ReceiptService;
import it.gov.pagopa.wispconverter.service.model.ReceiptDto;
import it.gov.pagopa.wispconverter.service.model.re.RePaymentContext;
import it.gov.pagopa.wispconverter.util.CommonUtility;
import it.gov.pagopa.wispconverter.util.Constants;
import it.gov.pagopa.wispconverter.util.MDCUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
@Component
public class PaymentTimeoutConsumer extends SBConsumer {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReceiptService receiptService;
    private final ReService reService;
    @Value("${azure.sb.wisp-payment-timeout-queue.connectionString}")
    private String connectionString;
    @Value("${azure.sb.queue.receiptTimer.name}")
    private String queueName;
    @Value("${disable-service-bus-receiver}")
    private boolean disableServiceBusReceiver;

    public PaymentTimeoutConsumer(ReceiptService receiptService, ReService reService) {
        this.receiptService = receiptService;
        this.reService = reService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeClient() {
        if (receiverClient != null && !disableServiceBusReceiver) {
            log.info("[Scheduled] Starting PaymentTimeoutConsumer {}", ZonedDateTime.now());
            receiverClient.start();
        }
    }

    @PostConstruct
    public void post() {
        if (StringUtils.isNotBlank(connectionString)
                && !connectionString.equals("-")
                && !disableServiceBusReceiver) {
            receiverClient =
                    CommonUtility.getServiceBusProcessorClient(
                            connectionString, queueName, this::processMessage, this::processError);
        }
    }

    public void processMessage(ServiceBusReceivedMessageContext context) {
        MDCUtil.setSessionDataInfo("payment-token-timeout-trigger");
        ServiceBusReceivedMessage message = context.getMessage();
        log.debug(
                "Processing message. Session: {}, Sequence #: {}. Contents: {}",
                message.getMessageId(),
                message.getSequenceNumber(),
                message.getBody());
        try {
            ReceiptDto receiptDto = mapper.readValue(message.getBody().toStream(), ReceiptDto.class);
            generateREForPaymentTokenTimeout(receiptDto);

            receiptService.sendKoPaaInviaRtToCreditorInstitution(List.of(receiptDto));
        } catch (IOException e) {
            log.error(
                    "Error when read ReceiptDto value from message: '{}'. Body: '{}'",
                    message.getMessageId(),
                    message.getBody());
        }
        MDC.clear();
    }

    private void generateREForPaymentTokenTimeout(ReceiptDto receipt) {
        MDC.put(Constants.MDC_PAYMENT_TOKEN, receipt.getPaymentToken());
        MDC.put(Constants.MDC_DOMAIN_ID, receipt.getFiscalCode());
        MDC.put(Constants.MDC_NOTICE_NUMBER, receipt.getNoticeNumber());
        reService.sendEvent(
                WorkflowStatus.PAYMENT_TOKEN_TIMER_IN_TIMEOUT,
                RePaymentContext.builder()
                        .paymentToken(receipt.getPaymentToken())
                        .domainId(receipt.getFiscalCode())
                        .noticeNumber(receipt.getNoticeNumber())
                        .build(),
                "Expired payment token. A KO receipt will be sent: " + receipt);
    }
}
