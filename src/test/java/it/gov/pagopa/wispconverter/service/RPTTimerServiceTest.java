package it.gov.pagopa.wispconverter.service;

import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import it.gov.pagopa.wispconverter.controller.model.RPTTimerRequest;
import it.gov.pagopa.wispconverter.repository.CacheRepository;
import it.gov.pagopa.wispconverter.repository.RPTRequestRepository;
import it.gov.pagopa.wispconverter.repository.model.RPTRequestEntity;
import it.gov.pagopa.wispconverter.service.model.ECommerceHangTimeoutMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RPTTimerServiceTest {

    public static final String SESSION_ID = "12345678-1234-1234-123456781234";
    public static final Long SEQUENCE_NUMBER = 1111L;


    @Mock
    private ReService reService;

    @Mock
    private ServiceBusSenderClient serviceBusSenderClient;

    @Mock
    private CacheRepository cacheRepository;

    @Mock
    private RPTRequestRepository rptRequestRepository;

    @InjectMocks
    @Spy
    private RPTTimerService rptTimerService;

    @Captor
    ArgumentCaptor<ServiceBusMessage> messageArgumentCaptor;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(rptTimerService, "connectionString", "-");
        ReflectionTestUtils.setField(rptTimerService, "queueName", "your-queue-name");
        ReflectionTestUtils.setField(rptTimerService, "serviceBusSenderClient", serviceBusSenderClient);
        ReflectionTestUtils.setField(rptTimerService, "expirationTime", 1800);
        ReflectionTestUtils.setField(rptTimerService, "reService", reService);
        ReflectionTestUtils.setField(rptTimerService, "cacheRepository", cacheRepository);
    }

    @Test
    void sendMessage_ok() {
        RPTTimerRequest message = RPTTimerRequest.builder()
                .sessionId(SESSION_ID)
                .build();
        RPTRequestEntity rptRequestEntity = RPTRequestEntity.builder()
                .id(SESSION_ID)
                .build();
        when(rptRequestRepository.findById(SESSION_ID)).thenReturn(Optional.of(rptRequestEntity));
        rptTimerService.sendMessage(message);
        verify(cacheRepository, times(1)).hasKey(eq("wisp_timer_rpt_" + SESSION_ID));
        verify(serviceBusSenderClient, times(1)).scheduleMessage(messageArgumentCaptor.capture(), any());
        assertEquals("{\"sessionId\":\"" + SESSION_ID+ "\"}",
                messageArgumentCaptor.getValue().getBody().toString());
    }

    @Test
    void sendMessage_messageDuplicated() {
        RPTTimerRequest message = RPTTimerRequest.builder()
                .sessionId(SESSION_ID)
                .build();
        RPTRequestEntity rptRequestEntity = RPTRequestEntity.builder()
                .id(SESSION_ID)
                .build();
        when(rptRequestRepository.findById(SESSION_ID)).thenReturn(Optional.of(rptRequestEntity));
        when(cacheRepository.hasKey(anyString())).thenReturn(Boolean.TRUE);
        when(cacheRepository.read(any(), any())).thenReturn(SEQUENCE_NUMBER.toString());

        rptTimerService.sendMessage(message);

        verify(cacheRepository, times(1)).delete(eq("wisp_timer_rpt_" + SESSION_ID));
        verify(serviceBusSenderClient, times(1)).cancelScheduledMessage(SEQUENCE_NUMBER);
        verify(cacheRepository, times(1)).hasKey(eq("wisp_timer_rpt_" + SESSION_ID));
        verify(serviceBusSenderClient, times(1)).scheduleMessage(messageArgumentCaptor.capture(), any());
    }

    @Test
    void cancelScheduledMessage_ok() {
        when(cacheRepository.read(any(), any())).thenReturn(SEQUENCE_NUMBER.toString());

        rptTimerService.cancelScheduledMessage(SESSION_ID);

        verify(cacheRepository, times(1)).delete(eq("wisp_timer_rpt_" + SESSION_ID));
        verify(serviceBusSenderClient, times(1)).cancelScheduledMessage(SEQUENCE_NUMBER);

    }
}