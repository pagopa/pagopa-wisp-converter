package it.gov.pagopa.wispconverter.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.wispconverter.Application;
import it.gov.pagopa.wispconverter.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles(profiles = "test")
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
public class RecoveryTest {
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

    @Test
    public void testRecoverReceiptKOForCreditorInstitution() throws Exception {
        String ci = "77777777777";
        String dateFrom = "2024-09-03";
        String dateTo = "2024-09-09";

        mockMvc.perform(post("/recovery/{creditor_institution}/receipt-ko", ci)
                                .queryParam("date_from", dateFrom)
                                .queryParam("date_to", dateTo))
                .andExpect(status().isOk());

        Mockito.verify(recoveryService, times(1)).recoverReceiptKOForCreditorInstitution(eq(ci), any(), any());
    }

    @Test
    public void testRecoverReceiptKOForCreditorInstitution_500() throws Exception {
        String ci = "77777777777";
        String dateFrom = "2024-09-03";
        String dateTo = "2024-09-09";

        Mockito.when(recoveryService.recoverReceiptKOForCreditorInstitution(anyString(), any(), any()))
                .thenThrow(new RuntimeException("Test exception"));

        mockMvc.perform(post("/recovery/{creditor_institution}/receipt-ko", ci)
                                .queryParam("date_from", dateFrom)
                                .queryParam("date_to", dateTo))
                .andExpect(status().isInternalServerError());

        Mockito.verify(recoveryService, times(1)).recoverReceiptKOForCreditorInstitution(eq(ci), any(), any());
    }
}