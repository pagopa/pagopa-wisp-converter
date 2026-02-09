package it.gov.pagopa.wispconverter.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.wispconverter.Application;
import it.gov.pagopa.wispconverter.config.client.AppInsightTelemetryClient;
import it.gov.pagopa.wispconverter.controller.model.RecoveryReceiptByPayloadRequest;
import it.gov.pagopa.wispconverter.repository.ReceiptDeadLetterRepository;
import it.gov.pagopa.wispconverter.service.*;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles(profiles = "test")
@SpringBootTest(classes = Application.class)
@RunWith(SpringJUnit4ClassRunner.class)
@AutoConfigureMockMvc
class RecoveryTest {
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
    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private RecoveryService recoveryService;
    @MockBean
    private ReceiptService receiptService;
    @MockBean
    private DecouplerService decouplerService;
    @MockBean
    private ReceiptDeadLetterRepository receiptDeadLetterRepository;
    @MockBean
    private AppInsightTelemetryClient telemetryClient;

    @Test
    void testRecoverReceiptKOForCreditorInstitution() throws Exception {
        String ci = "77777777777";
        String dateFrom = "2024-09-03";
        String dateTo = "2024-09-09";

        mockMvc.perform(post("/recovery/{creditor_institution}/receipt-ko", ci)
                        .queryParam("date_from", dateFrom)
                        .queryParam("date_to", dateTo))
                .andExpect(status().isOk());

        Mockito.verify(recoveryService, times(1)).recoverReceiptKOByCI(eq(ci), any(), any());
    }

    @Test
    void testRecoverReceiptKOForCreditorInstitution_500() throws Exception {
        String ci = "77777777777";
        String dateFrom = "2024-09-03";
        String dateTo = "2024-09-09";

        Mockito.when(recoveryService.recoverReceiptKOByCI(anyString(), any(), any()))
                .thenThrow(new RuntimeException("Test exception"));

        mockMvc.perform(post("/recovery/{creditor_institution}/receipt-ko", ci)
                        .queryParam("date_from", dateFrom)
                        .queryParam("date_to", dateTo))
                .andExpect(status().isInternalServerError());

        Mockito.verify(recoveryService, times(1)).recoverReceiptKOByCI(eq(ci), any(), any());
    }

    @Test
    void testRecoverReceiptKOForCreditorInstitutionAndIUV() throws Exception {
        String ci = "77777777777";
        String iuv = "00000000000000000";
        String dateFrom = "2024-09-03";
        String dateTo = "2024-09-09";

        mockMvc.perform(post("/recovery/{ci}/rpt/{iuv}/receipt-ko", ci, iuv)
                        .queryParam("date_from", dateFrom)
                        .queryParam("date_to", dateTo))
                .andExpect(status().isBadRequest());

        Mockito.verify(recoveryService, times(1)).recoverReceiptKOByIUV(eq(ci), eq(iuv), any(), any());
    }

    @Test
    void testRecoverReceiptKOForCreditorInstitutionAndIUV_500() throws Exception {
        String ci = "77777777777";
        String iuv = "00000000000000000";
        String dateFrom = "2024-09-03";
        String dateTo = "2024-09-09";

        Mockito.when(recoveryService.recoverReceiptKOByIUV(anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("Test exception"));

        mockMvc.perform(post("/recovery/{ci}/rpt/{iuv}/receipt-ko", ci, iuv)
                        .queryParam("date_from", dateFrom)
                        .queryParam("date_to", dateTo))
                .andExpect(status().isInternalServerError());

        Mockito.verify(recoveryService, times(1)).recoverReceiptKOByIUV(eq(ci), eq(iuv), any(), any());
    }

    @Test
    void recoverKoReceiptByPayload_200() throws Exception {
        RecoveryReceiptByPayloadRequest request = RecoveryReceiptByPayloadRequest.builder()
                .payload("mockPayloadGzip")
                .primitive("nodoInviaCarrelloRPT")
                .sessionId("mock-session-id").build();

        doNothing().when(receiptService).sendRTKoFromRPTRequestEntity(any());

        mockMvc.perform(post("/recovery/receipts/koByRpt")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(request)))
                .andExpect(status().isOk());
    }
}
