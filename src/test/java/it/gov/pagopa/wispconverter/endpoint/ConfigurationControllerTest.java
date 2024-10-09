package it.gov.pagopa.wispconverter.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.wispconverter.Application;
import it.gov.pagopa.wispconverter.controller.model.ConfigurationModel;
import it.gov.pagopa.wispconverter.service.ConfigurationService;
import it.gov.pagopa.wispconverter.service.PaaInviaRTSenderService;
import it.gov.pagopa.wispconverter.service.RecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.springframework.http.MediaType.APPLICATION_JSON;

@ActiveProfiles(profiles = "test")
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class ConfigurationControllerTest {

    @MockBean
    private ConfigurationService configurationService;

    @MockBean
    private RecoveryService recoveryService;

    @MockBean
    private PaaInviaRTSenderService paaInviaRTSenderService;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void getCreditorInstitutionsConfiguration() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/whitelist/cis"))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful())
                .andExpect(MockMvcResultMatchers.content().contentType(APPLICATION_JSON));
    }

    @Test
    void geteStationsConfiguration() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/whitelist/stations"))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful())
                .andExpect(MockMvcResultMatchers.content().contentType(APPLICATION_JSON));
    }

    @Test
    void createCreditorInstitutionsConfiguration() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/whitelist/cis")
                        .accept(APPLICATION_JSON)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConfigurationModel())))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful());
    }

    @Test
    void createStationsConfiguration() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/whitelist/stations")
                        .accept(APPLICATION_JSON)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConfigurationModel())))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful());
    }
}