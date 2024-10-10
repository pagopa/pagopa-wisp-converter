package it.gov.pagopa.wispconverter.service;

import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import it.gov.pagopa.wispconverter.controller.model.ReceiptTimerRequest;
import it.gov.pagopa.wispconverter.repository.CacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ReceiptTimerServiceTest {
    private static final String MOCK_CONNECTION_STRING = "Endpoint=sb://mock-servicebus.servicebus.windows.net/;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=mock-key";
    private static final String QUEUE_NAME = "your-queue-name";

    @Mock
    private CacheRepository cacheRepository;

    @Mock
    private ServiceBusSenderClient serviceBusSenderClient;

    @Mock
    private ReService reService;

    @Mock
    private ECommerceHangTimerService eCommerceHangTimerService;

    @InjectMocks
    private ReceiptTimerService receiptTimerService;

    @BeforeEach
    public void setup() {
        ReflectionTestUtils.setField(receiptTimerService, "connectionString", MOCK_CONNECTION_STRING);
        ReflectionTestUtils.setField(receiptTimerService, "queueName", "your-queue-name");
        ReflectionTestUtils.setField(receiptTimerService, "serviceBusSenderClient", serviceBusSenderClient);
        ReflectionTestUtils.setField(receiptTimerService, "reService", reService);
        ReflectionTestUtils.setField(receiptTimerService, "eCommerceHangTimerService", eCommerceHangTimerService);
    }

    @Test
    public void testSendMessage_duplicateMessage() {
        ReceiptTimerRequest request = new ReceiptTimerRequest();
        request.setPaymentToken("token123");
        request.setFiscalCode("fiscalCode");
        request.setNoticeNumber("noticeNumber");
        request.setExpirationTime(1000L);

        when(cacheRepository.read(any(String.class), eq(String.class))).thenReturn("existingSequenceNumber");

        receiptTimerService.sendMessage(request);

        verify(cacheRepository, times(0)).insert(any(String.class), any(String.class), any(Long.class), any(ChronoUnit.class));
    }

    @Test
    public void testSendMessage_newMessage() {
        ReceiptTimerRequest request = new ReceiptTimerRequest();
        request.setPaymentToken("token123");
        request.setSessionId("sessionId321");
        request.setFiscalCode("fiscalCode");
        request.setNoticeNumber("noticeNumber");
        request.setExpirationTime(1000L);

        when(cacheRepository.read(any(String.class), eq(String.class))).thenReturn(null);
        when(serviceBusSenderClient.scheduleMessage(any(ServiceBusMessage.class), any(OffsetDateTime.class))).thenReturn(123L);

        receiptTimerService.sendMessage(request);

        verify(cacheRepository, times(1)).insert(any(String.class), eq("123"), eq(1000L), eq(ChronoUnit.MILLIS));
        verify(eCommerceHangTimerService, times(1)).cancelScheduledMessage(eq("noticeNumber"), eq("fiscalCode"), eq("sessionId321"));
    }

    @Test
    public void testCancelScheduledMessage_callCancelScheduledMessage() {
        List<String> paymentTokens = List.of("token1", "token2");
        String sequenceNumberKey1 = String.format(ReceiptTimerService.CACHING_KEY_TEMPLATE, "token1");
        String sequenceNumberKey2 = String.format(ReceiptTimerService.CACHING_KEY_TEMPLATE, "token2");
        long sequenceNumber1 = 123L;
        long sequenceNumber2 = 456L;

        when(cacheRepository.read(sequenceNumberKey1, String.class)).thenReturn(Long.toString(sequenceNumber1));
        when(cacheRepository.read(sequenceNumberKey2, String.class)).thenReturn(Long.toString(sequenceNumber2));
        doNothing().when(serviceBusSenderClient).cancelScheduledMessage(anyLong());

        receiptTimerService.cancelScheduledMessage(paymentTokens);

        verify(cacheRepository, times(1)).delete(sequenceNumberKey1);
        verify(cacheRepository, times(1)).delete(sequenceNumberKey2);
    }

    @Test
    public void testCancelScheduledMessage_notFound() {
        List<String> paymentTokens = List.of("token1");

        when(cacheRepository.read(any(String.class), eq(String.class))).thenReturn(null);

        receiptTimerService.cancelScheduledMessage(paymentTokens);

        verify(cacheRepository, times(0)).delete(any(String.class));
    }
}


