package it.gov.pagopa.wispconverter;

import com.azure.cosmos.CosmosAsyncClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.wispconverter.controller.RedirectController;
import it.gov.pagopa.wispconverter.repository.*;
import it.gov.pagopa.wispconverter.service.PaaInviaRTSenderService;
import it.gov.pagopa.wispconverter.service.ReceiptService;
import it.gov.pagopa.wispconverter.service.RecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ActiveProfiles(profiles = "test")
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class OpenApiGenerationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RedirectController redirectController;

    @MockBean
    private ConfigurationRepository configurationRepository;

    @MockBean
    private CosmosAsyncClient cosmosAsyncClient;

    @MockBean
    private ReceiptService receiptService;

    @MockBean
    private RecoveryService recoveryService;

    @MockBean
    private PaaInviaRTSenderService paaInviaRTSenderService;

    @Test
    void swaggerSpringPlugin() throws Exception {
        saveOpenAPI("/v3/api-docs", "openapi.json");
        saveOpenAPI("/v3/api-docs/redirect", "openapi_redirect.json");
    }

    private void saveOpenAPI(String fromUri, String toFile) throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(fromUri).accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful())
                .andDo(
                        (result) -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                            final String content = result.getResponse().getContentAsString();
                            assertFalse(content.isBlank());
                            assertFalse(content.contains("${"), "Generated swagger contains placeholders");
                            Object swagger =
                                    objectMapper.readValue(result.getResponse().getContentAsString(), Object.class);
                            String formatted =
                                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(swagger);
                            Path basePath = Paths.get("openapi/");
                            Files.createDirectories(basePath);
                            Files.write(basePath.resolve(toFile), formatted.getBytes());
                        });
    }
}
