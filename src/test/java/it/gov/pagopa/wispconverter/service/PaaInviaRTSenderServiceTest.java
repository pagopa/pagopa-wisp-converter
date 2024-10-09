package it.gov.pagopa.wispconverter.service;

import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.ReceiptDeadLetterRepository;
import it.gov.pagopa.wispconverter.repository.model.ReceiptDeadLetterEntity;
import it.gov.pagopa.wispconverter.repository.model.enumz.ReceiptStatusEnum;
import it.gov.pagopa.wispconverter.util.JaxbElementUtil;
import org.junit.jupiter.api.Test;
import org.springframework.data.util.Pair;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.times;

class PaaInviaRTSenderServiceTest {

    @Test
    void esitoOK() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        ReService reService = mock(ReService.class);
        JaxbElementUtil jaxbElementUtil = new JaxbElementUtil();
        RtReceiptCosmosService rtReceiptCosmosService = mock(RtReceiptCosmosService.class);
        ReceiptDeadLetterRepository receiptDeadLetterRepository = mock(ReceiptDeadLetterRepository.class);

        RestClient client = mock(RestClient.class);
        when(builder.build()).thenReturn(client);
        RestClient.RequestBodyUriSpec requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        when(client.post()).thenReturn(requestBodyUriSpec);
        RestClient.RequestBodySpec requestBodySpec = mock(RestClient.RequestBodySpec.class);
        when(requestBodyUriSpec.uri(any(URI.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);

        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        String paaInviaRTRisposta = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><ns3:paaInviaRTRisposta xmlns:ns2=\"http://ws.pagamenti.telematici.gov/ppthead\" xmlns:ns3=\"http://ws.pagamenti.telematici.gov/\"><paaInviaRTRisposta><esito>OK</esito></paaInviaRTRisposta></ns3:paaInviaRTRisposta></soap:Body></soap:Envelope>";
        when(responseSpec.toEntity(String.class))
                .thenReturn(ResponseEntity.ok().body(paaInviaRTRisposta));

        PaaInviaRTSenderService p = new PaaInviaRTSenderService(builder, reService, rtReceiptCosmosService, jaxbElementUtil, receiptDeadLetterRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(p, "jaxbElementUtil", new JaxbElementUtil());
        p.sendToCreditorInstitution(URI.create("http://pagopa.mock.dev/"), null, List.of(Pair.of("soapaction", "paaInviaRT")), "", "", "", "");
        assertTrue(true);
        verify(rtReceiptCosmosService, times(1)).updateReceiptStatus(any(String.class), any(String.class), any(String.class), eq(ReceiptStatusEnum.SENT));
    }

    @Test
    void esitoKONoDeadLetter() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        ReService reService = mock(ReService.class);
        JaxbElementUtil jaxbElementUtil = new JaxbElementUtil();
        RtReceiptCosmosService rtReceiptCosmosService = mock(RtReceiptCosmosService.class);
        ReceiptDeadLetterRepository receiptDeadLetterRepository = mock(ReceiptDeadLetterRepository.class);
        RestClient client = mock(RestClient.class);
        when(builder.build()).thenReturn(client);
        RestClient.RequestBodyUriSpec requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        when(client.post()).thenReturn(requestBodyUriSpec);
        RestClient.RequestBodySpec requestBodySpec = mock(RestClient.RequestBodySpec.class);
        when(requestBodyUriSpec.uri(any(URI.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);

        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        String paaInviaRTRisposta = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><ns3:paaInviaRTRisposta xmlns:ns2=\"http://ws.pagamenti.telematici.gov/ppthead\" xmlns:ns3=\"http://ws.pagamenti.telematici.gov/\"><paaInviaRTRisposta><esito>KO</esito><fault><faultCode>PAA_RT_DUPLICATA</faultCode></fault></paaInviaRTRisposta></ns3:paaInviaRTRisposta></soap:Body></soap:Envelope>";
        when(responseSpec.toEntity(String.class))
                .thenReturn(ResponseEntity.ok().body(paaInviaRTRisposta));

        PaaInviaRTSenderService p = new PaaInviaRTSenderService(builder, reService, rtReceiptCosmosService, jaxbElementUtil, receiptDeadLetterRepository);
        List<String> noDeadLetterOnStates = Arrays.stream("PAA_RT_DUPLICATA,PAA_SYSTEM_ERROR".split(",")).toList();
        ReflectionTestUtils.setField(p, "noDeadLetterOnStates", noDeadLetterOnStates);
        p.sendToCreditorInstitution(URI.create("http://pagopa.mock.dev/"), null, List.of(Pair.of("soapaction", "paaInviaRT")), "", "12345678910", "02345678998765432", "CCP_TEST");
        assertTrue(true);
        verify(rtReceiptCosmosService, times(1)).updateReceiptStatus(any(String.class), any(String.class), any(String.class), eq(ReceiptStatusEnum.SENT_REJECTED_BY_EC));
        verify(receiptDeadLetterRepository, times(0)).save(any(ReceiptDeadLetterEntity.class));
    }

    @Test
    void esitoKODeadLetter() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        ReService reService = mock(ReService.class);
        JaxbElementUtil jaxbElementUtil = new JaxbElementUtil();
        RtReceiptCosmosService rtReceiptCosmosService = mock(RtReceiptCosmosService.class);
        ReceiptDeadLetterRepository receiptDeadLetterRepository = mock(ReceiptDeadLetterRepository.class);
        RestClient client = mock(RestClient.class);
        when(builder.build()).thenReturn(client);
        RestClient.RequestBodyUriSpec requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        when(client.post()).thenReturn(requestBodyUriSpec);
        RestClient.RequestBodySpec requestBodySpec = mock(RestClient.RequestBodySpec.class);
        when(requestBodyUriSpec.uri(any(URI.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);

        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        String paaInviaRTRisposta = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><ns3:paaInviaRTRisposta xmlns:ns2=\"http://ws.pagamenti.telematici.gov/ppthead\" xmlns:ns3=\"http://ws.pagamenti.telematici.gov/\"><paaInviaRTRisposta><esito>KO</esito><fault><faultCode>PAA_STATO_SCONOSCIUTO</faultCode></fault></paaInviaRTRisposta></ns3:paaInviaRTRisposta></soap:Body></soap:Envelope>";
        when(responseSpec.toEntity(String.class))
                .thenReturn(ResponseEntity.ok().body(paaInviaRTRisposta));

        PaaInviaRTSenderService p = new PaaInviaRTSenderService(builder, reService, rtReceiptCosmosService, jaxbElementUtil, receiptDeadLetterRepository);
        List<String> noDeadLetterOnStates = Arrays.stream("PAA_RT_DUPLICATA,PAA_SYSTEM_ERROR".split(",")).toList();
        ReflectionTestUtils.setField(p, "noDeadLetterOnStates", noDeadLetterOnStates);
        p.sendToCreditorInstitution(URI.create("http://pagopa.mock.dev/"), null, List.of(Pair.of("soapaction", "paaInviaRT")), "", "", "", "");
        assertTrue(true);
        verify(rtReceiptCosmosService, times(1)).updateReceiptStatus(any(String.class), any(String.class), any(String.class), eq(ReceiptStatusEnum.SENT_REJECTED_BY_EC));
        verify(receiptDeadLetterRepository, times(1)).save(any(ReceiptDeadLetterEntity.class));
    }

    @Test
    void esitoKORescheduled() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        ReService reService = mock(ReService.class);
        JaxbElementUtil jaxbElementUtil = new JaxbElementUtil();
        RtReceiptCosmosService rtReceiptCosmosService = mock(RtReceiptCosmosService.class);
        ReceiptDeadLetterRepository receiptDeadLetterRepository = mock(ReceiptDeadLetterRepository.class);
        RestClient client = mock(RestClient.class);
        when(builder.build()).thenReturn(client);
        RestClient.RequestBodyUriSpec requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        when(client.post()).thenReturn(requestBodyUriSpec);
        RestClient.RequestBodySpec requestBodySpec = mock(RestClient.RequestBodySpec.class);
        when(requestBodyUriSpec.uri(any(URI.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);

        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        String paaInviaRTRisposta = "WRONG_BODY";
        when(responseSpec.toEntity(String.class))
                .thenReturn(ResponseEntity.ok().body(paaInviaRTRisposta));

        PaaInviaRTSenderService p = new PaaInviaRTSenderService(builder, reService, rtReceiptCosmosService, jaxbElementUtil, receiptDeadLetterRepository);
        List<String> noDeadLetterOnStates = Arrays.stream("PAA_RT_DUPLICATA,PAA_SYSTEM_ERROR".split(",")).toList();
        ReflectionTestUtils.setField(p, "noDeadLetterOnStates", noDeadLetterOnStates);
        try {
            p.sendToCreditorInstitution(URI.create("http://pagopa.mock.dev/"), null, List.of(Pair.of("soapaction", "paaInviaRT")), "", "", "", "");
            fail();
        } catch (AppException e){
            assertTrue(true);
        }
        verify(receiptDeadLetterRepository, times(0)).save(any(ReceiptDeadLetterEntity.class));
    }
}
