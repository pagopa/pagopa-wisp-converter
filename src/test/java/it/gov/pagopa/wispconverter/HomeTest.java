package it.gov.pagopa.wispconverter;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.wispconverter.controller.model.AppInfoResponse;
import it.gov.pagopa.wispconverter.service.PaaInviaRTSenderService;
import it.gov.pagopa.wispconverter.service.ReceiptService;
import it.gov.pagopa.wispconverter.service.RecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles(profiles = "test")
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class HomeTest {

    @Autowired
    ObjectMapper objectMapper;
    @Value("${info.application.name}")
    private String name;
    @Value("${info.application.version}")
    private String version;
    @Value("${info.properties.environment}")
    private String environment;
    @Autowired
    private MockMvc mvc;

    @MockBean
    private ReceiptService receiptService;

    @MockBean
    private RecoveryService recoveryService;

    @MockBean
    private PaaInviaRTSenderService paaInviaRTSenderService;


    @Test
    void slash() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is3xxRedirection());

    }

    @Test
    void info() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful()).andDo(
                        result -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                            final String content = result.getResponse().getContentAsString();
                            assertFalse(content.isBlank());
                            assertFalse(content.contains("${"), "Generated swagger contains placeholders");
                            AppInfoResponse info = objectMapper.readValue(result.getResponse().getContentAsString(), AppInfoResponse.class);
                            assertEquals(info.getName(), name);
                            assertEquals(info.getEnvironment(), environment);
                            assertEquals(info.getVersion(), version);
                        });

    }
}
