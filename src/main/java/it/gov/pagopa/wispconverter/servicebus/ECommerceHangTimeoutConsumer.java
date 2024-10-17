package it.gov.pagopa.wispconverter.servicebus;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.wispconverter.repository.model.enumz.InternalStepStatus;
import it.gov.pagopa.wispconverter.service.ReceiptService;
import it.gov.pagopa.wispconverter.service.model.ECommerceHangTimeoutMessage;
import it.gov.pagopa.wispconverter.service.model.ReceiptDto;
import it.gov.pagopa.wispconverter.util.CommonUtility;
import it.gov.pagopa.wispconverter.util.Constants;
import it.gov.pagopa.wispconverter.util.MDCUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;

import static it.gov.pagopa.wispconverter.util.SchedulerUtils.*;

/**
 * This class is the consumer of the queue 'ecommerce-hang-timeout' of the service bus.
 * When a schedule message trigger this class we'll expire the payment,
 * and we'll send a sendRT-Negative to the Creditor Institution
 */
@Slf4j
@Component
public class ECommerceHangTimeoutConsumer extends SBConsumer {

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${azure.sb.wisp-ecommerce-hang-timeout-queue.connectionString}")
    private String connectionString;

    @Value("${azure.sb.queue.ecommerce-hang-timeout.name}")
    private String queueName;

    @Autowired
    private ReceiptService receiptService;


    @PostConstruct
    public void post() {
        if (StringUtils.isNotBlank(connectionString) && !connectionString.equals("-")) {
            receiverClient = CommonUtility.getServiceBusProcessorClient(connectionString, queueName, this::processMessage, this::processError);
        }
    }


    @EventListener(ApplicationReadyEvent.class)
    public void initializeClient() {
        if (receiverClient != null) {
        	updateMDCForStartExecution("initializeClient", "[Scheduled] Starting ECommerceHangTimeoutConsumer "+ ZonedDateTime.now());
            receiverClient.start();
        }
    }

    public void processMessage(ServiceBusReceivedMessageContext context) {
        MDCUtil.setSessionDataInfo("ecommerce-hang-timeout-trigger");
        ServiceBusReceivedMessage message = context.getMessage();
        log.debug("Processing message. Session: {}, Sequence #: {}. Contents: {}", message.getMessageId(), message.getSequenceNumber(), message.getBody());
        try {
            // read the message
            ECommerceHangTimeoutMessage timeoutMessage = mapper.readValue(message.getBody().toStream(), ECommerceHangTimeoutMessage.class);

            // log event
            MDC.put(Constants.MDC_DOMAIN_ID, timeoutMessage.getFiscalCode());
            MDC.put(Constants.MDC_NOTICE_NUMBER, timeoutMessage.getNoticeNumber());
            generateRE(InternalStepStatus.ECOMMERCE_HANG_TIMER_TRIGGER, "Expired eCommerce hang timer. A Negative sendRT will be sent: " + timeoutMessage);

            // transform to string list
            var inputPaaInviaRTKo = List.of(ReceiptDto.builder()
                    .fiscalCode(timeoutMessage.getFiscalCode())
                    .noticeNumber(timeoutMessage.getNoticeNumber())
                    .build());
            receiptService.sendKoPaaInviaRtToCreditorInstitution(inputPaaInviaRTKo);
        } catch (IOException e) {
        	updateMDCError(e, "Error when read ECommerceHangTimeoutDto value from message: '"+message.getMessageId()+"'. Body: '"+message.getBody()+"'");
        }
        updateMDCForEndExecution();
        MDC.clear();
    }


}