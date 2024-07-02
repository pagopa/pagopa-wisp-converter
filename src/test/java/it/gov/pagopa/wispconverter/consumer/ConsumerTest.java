package it.gov.pagopa.wispconverter.consumer;

import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.*;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.IdempotencyKeyRepository;
import it.gov.pagopa.wispconverter.repository.RTRequestRepository;
import it.gov.pagopa.wispconverter.repository.model.IdempotencyKeyEntity;
import it.gov.pagopa.wispconverter.repository.model.RTRequestEntity;
import it.gov.pagopa.wispconverter.repository.model.enumz.IdempotencyStatusEnum;
import it.gov.pagopa.wispconverter.repository.model.enumz.ReceiptTypeEnum;
import it.gov.pagopa.wispconverter.service.*;
import it.gov.pagopa.wispconverter.servicebus.RTConsumer;
import it.gov.pagopa.wispconverter.util.AppBase64Util;
import it.gov.pagopa.wispconverter.util.JaxbElementUtil;
import it.gov.pagopa.wispconverter.util.ZipUtil;
import it.gov.pagopa.wispconverter.utils.TestUtils;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ConsumerTest {

    @SneakyThrows
    private RTRequestEntity getStoredReceipt(int retries, String rawtype, String url) {
        ReceiptTypeEnum type = ReceiptTypeEnum.valueOf(rawtype.toUpperCase());
        String payload = TestUtils.loadFileContent("/requests/paaInviaRT.xml");
        return RTRequestEntity.builder()
                .payload(AppBase64Util.base64Encode(ZipUtil.zip(payload)))
                .retry(retries)
                .receiptType(type)
                .url(url)
                .headers(List.of("soapaction:paaInviaRT"))
                .idempotencyKey("idpa_uuid_nav")
                .build();
    }

    @ParameterizedTest
    @CsvSource(value = {"ok", "ko"})
    void sendToPa_sent(String receiptType) {

        ServiceBusReceivedMessageContext messageContext = mock(ServiceBusReceivedMessageContext.class);
        ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        when(messageContext.getMessage()).thenReturn(message);
        BinaryData bindata = mock(BinaryData.class);
        when(message.getBody()).thenReturn(bindata);
        when(message.getSubject()).thenReturn("mystation");
        when(bindata.toBytes()).thenReturn("aaaaa_bbbbb_ccccc".getBytes(StandardCharsets.UTF_8));

        ReService reService = mock(ReService.class);
        doNothing().when(reService).addRe(any());

        IdempotencyKeyRepository idempotencyKeyRepository = mock(IdempotencyKeyRepository.class);
        IdempotencyService idempotencyService = new IdempotencyService(idempotencyKeyRepository);
        when(idempotencyKeyRepository.findById(any(), any())).thenReturn(Optional.of(IdempotencyKeyEntity.builder()
                .status(IdempotencyStatusEnum.FAILED)
                .receiptType("OK".equalsIgnoreCase(receiptType) ? ReceiptTypeEnum.OK : ReceiptTypeEnum.KO)
                .lockedAt(null)
                .build()));
        ReflectionTestUtils.setField(idempotencyService, "lockValidityInMinutes", 10);
        ReflectionTestUtils.setField(idempotencyService, "idempotencyKeyRepository", idempotencyKeyRepository);

        JaxbElementUtil jaxbElementUtil = new JaxbElementUtil();

        RTRequestRepository rtRequestRepository = mock(RTRequestRepository.class);
        when(rtRequestRepository.findById(any(), any())).thenReturn(Optional.ofNullable(getStoredReceipt(0, receiptType, "http://endpoint:443")));
        RtCosmosService rtCosmosService = new RtCosmosService(reService, rtRequestRepository);
        ReflectionTestUtils.setField(rtCosmosService, "isTracingOnREEnabled", true);

        ServiceBusService serviceBusService = new ServiceBusService();
        ServiceBusSenderClient serviceBusSenderClient = mock(ServiceBusSenderClient.class);
        doNothing().when(serviceBusSenderClient).sendMessage(any());
        ReflectionTestUtils.setField(serviceBusService, "serviceBusSenderClient", serviceBusSenderClient);

        RTConsumer rtConsumer = new RTConsumer();
        ConfigCacheService ccs = mock(ConfigCacheService.class);
        when(ccs.getConfigData()).thenReturn(TestUtils.configData("mystation"));

        PaaInviaRTSenderService paaInviaRTSenderService = mock(PaaInviaRTSenderService.class);

        ReflectionTestUtils.setField(rtConsumer, "rtCosmosService", rtCosmosService);
        ReflectionTestUtils.setField(rtConsumer, "paaInviaRTSenderService", paaInviaRTSenderService);
        ReflectionTestUtils.setField(rtConsumer, "serviceBusService", serviceBusService);
        ReflectionTestUtils.setField(rtConsumer, "reService", reService);
        ReflectionTestUtils.setField(rtConsumer, "idempotencyService", idempotencyService);
        ReflectionTestUtils.setField(rtConsumer, "jaxbElementUtil", jaxbElementUtil);
        ReflectionTestUtils.setField(rtConsumer, "maxRetries", 48);

        rtConsumer.processMessage(messageContext);

        //verify(idempotencyService, times(1)).lockIdempotencyKey(any(), any());
        verify(rtRequestRepository, times(1)).findById(any(), any());
        verify(rtRequestRepository, times(1)).delete(any());
        verify(rtRequestRepository, times(0)).save(any());
        verify(serviceBusSenderClient, times(0)).sendMessage(any(), any());
        verify(idempotencyKeyRepository, times(2)).save(any());
    }

    @ParameterizedTest
    @CsvSource(value = {"ok, false", "ko, false", "ok, true", "ko, true"})
    void sendToPa_locked(String receiptType, String rawIsCompleted) {

        Boolean isCompleted = Boolean.valueOf(rawIsCompleted);
        ServiceBusReceivedMessageContext messageContext = mock(ServiceBusReceivedMessageContext.class);
        ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        when(messageContext.getMessage()).thenReturn(message);
        BinaryData bindata = mock(BinaryData.class);
        when(message.getBody()).thenReturn(bindata);
        when(message.getSubject()).thenReturn("mystation");
        when(bindata.toBytes()).thenReturn("aaaaa_bbbbb_ccccc".getBytes(StandardCharsets.UTF_8));

        ReService reService = mock(ReService.class);
        doNothing().when(reService).addRe(any());

        IdempotencyKeyRepository idempotencyKeyRepository = mock(IdempotencyKeyRepository.class);
        IdempotencyService idempotencyService = new IdempotencyService(idempotencyKeyRepository);
        when(idempotencyKeyRepository.findById(any(), any())).thenReturn(Optional.of(IdempotencyKeyEntity.builder()
                .status(isCompleted ? IdempotencyStatusEnum.SUCCESS : IdempotencyStatusEnum.LOCKED)
                .receiptType("OK".equalsIgnoreCase(receiptType) ? ReceiptTypeEnum.OK : ReceiptTypeEnum.KO)
                .lockedAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                .build()));
        ReflectionTestUtils.setField(idempotencyService, "lockValidityInMinutes", 10);
        ReflectionTestUtils.setField(idempotencyService, "idempotencyKeyRepository", idempotencyKeyRepository);

        JaxbElementUtil jaxbElementUtil = new JaxbElementUtil();

        RTRequestRepository rtRequestRepository = mock(RTRequestRepository.class);
        when(rtRequestRepository.findById(any(), any())).thenReturn(Optional.ofNullable(getStoredReceipt(0, receiptType, "http://endpoint:443")));
        RtCosmosService rtCosmosService = new RtCosmosService(reService, rtRequestRepository);
        ReflectionTestUtils.setField(rtCosmosService, "isTracingOnREEnabled", true);

        ServiceBusService serviceBusService = new ServiceBusService();
        ServiceBusSenderClient serviceBusSenderClient = mock(ServiceBusSenderClient.class);
        doNothing().when(serviceBusSenderClient).sendMessage(any());
        ReflectionTestUtils.setField(serviceBusService, "serviceBusSenderClient", serviceBusSenderClient);

        RTConsumer rtConsumer = new RTConsumer();
        ConfigCacheService ccs = mock(ConfigCacheService.class);
        when(ccs.getConfigData()).thenReturn(TestUtils.configData("mystation"));

        PaaInviaRTSenderService paaInviaRTSenderService = mock(PaaInviaRTSenderService.class);

        ReflectionTestUtils.setField(rtConsumer, "rtCosmosService", rtCosmosService);
        ReflectionTestUtils.setField(rtConsumer, "paaInviaRTSenderService", paaInviaRTSenderService);
        ReflectionTestUtils.setField(rtConsumer, "serviceBusService", serviceBusService);
        ReflectionTestUtils.setField(rtConsumer, "reService", reService);
        ReflectionTestUtils.setField(rtConsumer, "idempotencyService", idempotencyService);
        ReflectionTestUtils.setField(rtConsumer, "jaxbElementUtil", jaxbElementUtil);
        ReflectionTestUtils.setField(rtConsumer, "maxRetries", 48);

        rtConsumer.processMessage(messageContext);

        verify(rtRequestRepository, times(1)).findById(any(), any());
        verify(rtRequestRepository, times(0)).delete(any());
        verify(rtRequestRepository, times(isCompleted ? 0 : 1)).save(any());
        verify(serviceBusSenderClient, times(0)).sendMessage(any(), any());
        verify(idempotencyKeyRepository, times(0)).save(any());
    }

    @ParameterizedTest
    @CsvSource(value = {"ok", "ko"})
    void sendToPa_onError_reschedulable(String receiptType) {

        ServiceBusReceivedMessageContext messageContext = mock(ServiceBusReceivedMessageContext.class);
        ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        when(messageContext.getMessage()).thenReturn(message);
        BinaryData bindata = mock(BinaryData.class);
        when(message.getBody()).thenReturn(bindata);
        when(message.getSubject()).thenReturn("mystation");
        when(bindata.toBytes()).thenReturn("aaaaa_bbbbb_ccccc".getBytes(StandardCharsets.UTF_8));

        ReService reService = mock(ReService.class);
        doNothing().when(reService).addRe(any());

        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        when(idempotencyService.isIdempotencyKeyProcessable(any(), any())).thenReturn(true);

        JaxbElementUtil jaxbElementUtil = new JaxbElementUtil();

        RTRequestRepository rtRequestRepository = mock(RTRequestRepository.class);
        when(rtRequestRepository.findById(any(), any())).thenReturn(Optional.ofNullable(getStoredReceipt(0, receiptType, "http://endpoint:443")));
        RtCosmosService rtCosmosService = new RtCosmosService(reService, rtRequestRepository);
        ReflectionTestUtils.setField(rtCosmosService, "isTracingOnREEnabled", true);

        ServiceBusService serviceBusService = new ServiceBusService();
        ServiceBusSenderClient serviceBusSenderClient = mock(ServiceBusSenderClient.class);
        doNothing().when(serviceBusSenderClient).sendMessage(any());
        ReflectionTestUtils.setField(serviceBusService, "serviceBusSenderClient", serviceBusSenderClient);

        RTConsumer rtConsumer = new RTConsumer();
        ConfigCacheService ccs = mock(ConfigCacheService.class);
        when(ccs.getConfigData()).thenReturn(TestUtils.configData("mystation"));

        PaaInviaRTSenderService paaInviaRTSenderService = mock(PaaInviaRTSenderService.class);
        doThrow(new AppException(AppErrorCodeMessageEnum.PARSING_GENERIC_ERROR)).when(paaInviaRTSenderService).sendToCreditorInstitution(any(), any(), any());

        ReflectionTestUtils.setField(rtConsumer, "rtCosmosService", rtCosmosService);
        ReflectionTestUtils.setField(rtConsumer, "paaInviaRTSenderService", paaInviaRTSenderService);
        ReflectionTestUtils.setField(rtConsumer, "serviceBusService", serviceBusService);
        ReflectionTestUtils.setField(rtConsumer, "reService", reService);
        ReflectionTestUtils.setField(rtConsumer, "idempotencyService", idempotencyService);
        ReflectionTestUtils.setField(rtConsumer, "jaxbElementUtil", jaxbElementUtil);
        ReflectionTestUtils.setField(rtConsumer, "maxRetries", 48);

        rtConsumer.processMessage(messageContext);

        verify(idempotencyService, times(1)).lockIdempotencyKey(any(), any());
        verify(rtRequestRepository, times(1)).findById(any(), any());
        verify(rtRequestRepository, times(0)).delete(any());
        verify(rtRequestRepository, times(1)).save(any());
        verify(serviceBusSenderClient, times(0)).sendMessage(any(), any());
        verify(idempotencyService, times(1)).unlockIdempotencyKey(any(), any(), any());

    }

    @ParameterizedTest
    @CsvSource(value = {"ok", "ko"})
    void sendToPa_onError_notReschedulable(String receiptType) {

        ServiceBusReceivedMessageContext messageContext = mock(ServiceBusReceivedMessageContext.class);
        ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        when(messageContext.getMessage()).thenReturn(message);
        BinaryData bindata = mock(BinaryData.class);
        when(message.getBody()).thenReturn(bindata);
        when(message.getSubject()).thenReturn("mystation");
        when(bindata.toBytes()).thenReturn("aaaaa_bbbbb_ccccc".getBytes(StandardCharsets.UTF_8));


        ReService reService = mock(ReService.class);
        doNothing().when(reService).addRe(any());

        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        when(idempotencyService.isIdempotencyKeyProcessable(any(), any())).thenReturn(true);

        JaxbElementUtil jaxbElementUtil = new JaxbElementUtil();

        RTRequestRepository rtRequestRepository = mock(RTRequestRepository.class);
        when(rtRequestRepository.findById(any(), any())).thenReturn(Optional.ofNullable(getStoredReceipt(48, receiptType, "http://endpoint:443")));
        RtCosmosService rtCosmosService = new RtCosmosService(reService, rtRequestRepository);
        ReflectionTestUtils.setField(rtCosmosService, "isTracingOnREEnabled", true);

        ServiceBusService serviceBusService = new ServiceBusService();
        ServiceBusSenderClient serviceBusSenderClient = mock(ServiceBusSenderClient.class);
        doNothing().when(serviceBusSenderClient).sendMessage(any());
        ReflectionTestUtils.setField(serviceBusService, "serviceBusSenderClient", serviceBusSenderClient);

        RTConsumer rtConsumer = new RTConsumer();
        ConfigCacheService ccs = mock(ConfigCacheService.class);
        when(ccs.getConfigData()).thenReturn(TestUtils.configData("mystation"));

        PaaInviaRTSenderService paaInviaRTSenderService = mock(PaaInviaRTSenderService.class);
        doThrow(new AppException(AppErrorCodeMessageEnum.PARSING_GENERIC_ERROR)).when(paaInviaRTSenderService).sendToCreditorInstitution(any(), any(), any());

        ReflectionTestUtils.setField(rtConsumer, "rtCosmosService", rtCosmosService);
        ReflectionTestUtils.setField(rtConsumer, "paaInviaRTSenderService", paaInviaRTSenderService);
        ReflectionTestUtils.setField(rtConsumer, "serviceBusService", serviceBusService);
        ReflectionTestUtils.setField(rtConsumer, "reService", reService);
        ReflectionTestUtils.setField(rtConsumer, "idempotencyService", idempotencyService);
        ReflectionTestUtils.setField(rtConsumer, "jaxbElementUtil", jaxbElementUtil);
        ReflectionTestUtils.setField(rtConsumer, "maxRetries", 48);

        rtConsumer.processMessage(messageContext);

        verify(idempotencyService, times(1)).lockIdempotencyKey(any(), any());
        verify(rtRequestRepository, times(1)).findById(any(), any());
        verify(rtRequestRepository, times(0)).delete(any());
        verify(rtRequestRepository, times(0)).save(any());
        verify(serviceBusSenderClient, times(0)).sendMessage(any(), any());
        verify(idempotencyService, times(1)).unlockIdempotencyKey(any(), any(), any());
    }

    @Test
    void testprocesserror() {
        ServiceBusErrorContext serviceBusErrorContext = mock(ServiceBusErrorContext.class);
        when(serviceBusErrorContext.getException())
                .thenReturn(new ServiceBusException(new RuntimeException(), ServiceBusErrorSource.UNKNOWN));
        when(serviceBusErrorContext.getErrorSource()).thenReturn(ServiceBusErrorSource.COMPLETE);
        new RTConsumer().processError(serviceBusErrorContext);
        assertTrue(true);
    }

    @Test
    void testprocesserror2() {
        ServiceBusErrorContext serviceBusErrorContext = mock(ServiceBusErrorContext.class);
        when(serviceBusErrorContext.getException())
                .thenReturn(new ServiceBusException(new AmqpException(true, AmqpErrorCondition.MESSAGE_LOCK_LOST, "", null), ServiceBusErrorSource.UNKNOWN));
        when(serviceBusErrorContext.getErrorSource()).thenReturn(ServiceBusErrorSource.COMPLETE);
        new RTConsumer().processError(serviceBusErrorContext);
        assertTrue(true);
    }

    @Test
    void testprocesserror3() {
        ServiceBusErrorContext serviceBusErrorContext = mock(ServiceBusErrorContext.class);
        when(serviceBusErrorContext.getException())
                .thenReturn(new ServiceBusException(new AmqpException(true, AmqpErrorCondition.UNAUTHORIZED_ACCESS, "", null), ServiceBusErrorSource.UNKNOWN));
        when(serviceBusErrorContext.getErrorSource()).thenReturn(ServiceBusErrorSource.COMPLETE);
        new RTConsumer().processError(serviceBusErrorContext);
        assertTrue(true);
    }

    @Test
    void testprocesserror4() {
        ServiceBusErrorContext serviceBusErrorContext = mock(ServiceBusErrorContext.class);
        when(serviceBusErrorContext.getException())
                .thenReturn(new ServiceBusException(new AmqpException(true, AmqpErrorCondition.SERVER_BUSY_ERROR, "", null), ServiceBusErrorSource.UNKNOWN));
        when(serviceBusErrorContext.getErrorSource()).thenReturn(ServiceBusErrorSource.COMPLETE);
        new RTConsumer().processError(serviceBusErrorContext);
        assertTrue(true);
    }

    @Test
    void testprocesserror5() {
        ServiceBusErrorContext serviceBusErrorContext = mock(ServiceBusErrorContext.class);
        when(serviceBusErrorContext.getException())
                .thenReturn(
                        new RuntimeException());
        when(serviceBusErrorContext.getErrorSource()).thenReturn(ServiceBusErrorSource.COMPLETE);
        new RTConsumer().processError(serviceBusErrorContext);
        assertTrue(true);
    }

}
