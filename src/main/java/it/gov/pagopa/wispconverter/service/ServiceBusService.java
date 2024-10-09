package it.gov.pagopa.wispconverter.service;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.ZonedDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class ServiceBusService {

    @Value("${azure.sb.paaInviaRT.name}")
    private String queueName;

    @Value("${disable-service-bus}")
    private boolean disableServiceBus;

    private ServiceBusSenderClient serviceBusSenderClient;

    /*
        Service Bus send message to paainviart Queue
    */
    public void sendMessage(String message, Integer scheduledTimeInMinutes) {
        if (!disableServiceBus) {
            ServiceBusMessage serviceBusMessage = new ServiceBusMessage(message);
            if (scheduledTimeInMinutes != null) {
                serviceBusMessage.setScheduledEnqueueTime(ZonedDateTime.now().plusMinutes(scheduledTimeInMinutes).toOffsetDateTime());
            }
            log.debug("Rescheduling message [{}] at {}", message, serviceBusMessage.getScheduledEnqueueTime());
            serviceBusSenderClient.sendMessage(serviceBusMessage);
            log.debug("Rescheduled receipt [{}] at {} to the queue [{}]", message, serviceBusMessage.getScheduledEnqueueTime(), queueName);
        }
    }
}
