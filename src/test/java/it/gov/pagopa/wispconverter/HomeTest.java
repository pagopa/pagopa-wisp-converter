package it.gov.pagopa.wispconverter;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigDataV1Dto;
import it.gov.pagopa.wispconverter.config.client.AppInsightTelemetryClient;
import it.gov.pagopa.wispconverter.controller.model.AppInfoResponse;
import it.gov.pagopa.wispconverter.repository.CacheRepository;
import it.gov.pagopa.wispconverter.repository.NavToIuvMappingRepository;
import it.gov.pagopa.wispconverter.repository.RPTRequestRepository;
import it.gov.pagopa.wispconverter.repository.ReceiptDeadLetterRepository;
import it.gov.pagopa.wispconverter.service.*;
import it.gov.pagopa.wispconverter.service.mapper.RTMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import static org.mockito.Mockito.when;

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
    @MockBean
    RtReceiptCosmosService rtReceiptCosmosService;
    @MockBean
    RecoveryService recoveryService;
    @MockBean
    ReceiptDeadLetterRepository receiptDeadLetterRepository;
    @Autowired
    private MockMvc mvc;
    @MockBean
    private ConfigCacheService configCacheService;
    @MockBean
    private RPTRequestRepository rptRequestRepository;
    @MockBean
    private PaaInviaRTSenderService paaInviaRTSenderService;
    @MockBean
    private CacheRepository cacheRepository;
    @MockBean
    private NavToIuvMappingRepository navToIuvMappingRepository;
    @MockBean
    private ReService reService;
    @MockBean
    private RTMapper rtMapper;
    @MockBean
    private AppInsightTelemetryClient telemetryClient;

    @MockBean
    @Qualifier("decouplerCachingClient")
    private it.gov.pagopa.gen.wispconverter.client.decouplercaching.invoker.ApiClient decouplerCachingClient;

    @Test
    @SneakyThrows
    void slash() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is3xxRedirection());

    }

    @Test
    void info() throws Exception {
        when(configCacheService.getConfigData()).thenReturn(new ConfigDataV1Dto());
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
