package it.gov.pagopa.wispconverter.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.wispconverter.Application;
import it.gov.pagopa.wispconverter.config.client.AppInsightTelemetryClient;
import it.gov.pagopa.wispconverter.controller.model.ReceiptTimerRequest;
import it.gov.pagopa.wispconverter.repository.ReceiptDeadLetterRepository;
import it.gov.pagopa.wispconverter.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles(profiles = "test")
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
public class ReceiptTimerTest {
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ConfigCacheService configCacheService;

    @MockBean
    private PaaInviaRTSenderService paaInviaRTService;
    @MockBean
    private ServiceBusService paaInviaRTServiceBusService;

    @MockBean
    private AppInsightTelemetryClient telemetryClient;

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private ReceiptTimerService receiptTimerService;
    @MockBean
    private ReceiptService receiptService;
    @MockBean
    private RecoveryService recoveryService;
    @MockBean
    private DecouplerService decouplerService;
    @MockBean
    private ReceiptDeadLetterRepository receiptDeadLetterRepository;

    /*
     * CREATE receipt/timer
     * */
    @Test
    public void testCreateTimer() throws Exception {
        ReceiptTimerRequest request = ReceiptTimerRequest.builder()
                .expirationTime(1000L)
                .fiscalCode("77777777777")
                .noticeNumber("348123456789123456")
                .paymentToken("token123").build();

        mockMvc.perform(post("/receipt/timer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        Mockito.verify(receiptTimerService, times(1)).sendMessage(Mockito.any(ReceiptTimerRequest.class));
    }

    @Test
    public void testCreateTimerInvalidInput() throws Exception {
        ReceiptTimerRequest request = null;

        mockMvc.perform(post("/receipt/timer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        Mockito.verify(receiptTimerService, times(0)).sendMessage(Mockito.any(ReceiptTimerRequest.class));
    }

    /*
     * DELETE receipt/timer
     * */
    @Test
    public void testDeleteTimer() throws Exception {
        List<String> paymentTokens = List.of("token1", "token2");

        mockMvc.perform(delete("/receipt/timer")
                        .param("paymentTokens", "token1,token2")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        Mockito.verify(receiptTimerService, times(1)).cancelScheduledMessage(Mockito.eq(paymentTokens));
    }

    @Test
    public void testDeleteTimerServiceException() throws Exception {
        List<String> paymentTokens = List.of("token1", "token2");

        // Simulate an exception thrown by the service
        doThrow(new RuntimeException("Service exception")).when(receiptTimerService).cancelScheduledMessage(Mockito.eq(paymentTokens));

        mockMvc.perform(delete("/receipt/timer")
                        .param("paymentTokens", "token1,token2")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError());

        Mockito.verify(receiptTimerService, times(1)).cancelScheduledMessage(Mockito.eq(paymentTokens));
    }
}
