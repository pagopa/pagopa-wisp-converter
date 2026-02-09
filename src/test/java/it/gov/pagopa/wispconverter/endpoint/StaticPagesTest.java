package it.gov.pagopa.wispconverter.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.wispconverter.Application;
import it.gov.pagopa.wispconverter.config.client.AppInsightTelemetryClient;
import it.gov.pagopa.wispconverter.repository.ReceiptDeadLetterRepository;
import it.gov.pagopa.wispconverter.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles(profiles = "test")
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
public class StaticPagesTest {
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

    /*
     * GET static/error
     * */
    @Test
    public void testErrorStaticPage() throws Exception {

        mockMvc.perform(get("/static/error"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"));
    }
}
