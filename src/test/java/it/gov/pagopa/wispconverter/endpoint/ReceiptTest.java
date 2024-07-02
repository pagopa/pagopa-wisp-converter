package it.gov.pagopa.wispconverter.endpoint;

import com.azure.cosmos.CosmosClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.gen.wispconverter.client.cache.model.StationCreditorInstitutionDto;
import it.gov.pagopa.wispconverter.Application;
import it.gov.pagopa.wispconverter.controller.model.ReceiptRequest;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.*;
import it.gov.pagopa.wispconverter.repository.model.RPTRequestEntity;
import it.gov.pagopa.wispconverter.service.ConfigCacheService;
import it.gov.pagopa.wispconverter.service.PaaInviaRTSenderService;
import it.gov.pagopa.wispconverter.service.ReceiptTimerService;
import it.gov.pagopa.wispconverter.service.ServiceBusService;
import it.gov.pagopa.wispconverter.service.model.ReceiptDto;
import it.gov.pagopa.wispconverter.utils.TestUtils;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ActiveProfiles(profiles = "test")
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class ReceiptTest {

    private static final String STATION_ID = "mystation";
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ConfigCacheService configCacheService;
    @MockBean
    private ApplicationStartup applicationStartup;
    @MockBean
    private RPTRequestRepository rptRequestRepository;
    @MockBean
    private RTRequestRepository rtRequestRepository;
    @MockBean
    private IdempotencyKeyRepository idempotencyKeyRepository;
    @MockBean
    private it.gov.pagopa.gen.wispconverter.client.iuvgenerator.invoker.ApiClient iuveneratorClient;
    @MockBean
    private it.gov.pagopa.gen.wispconverter.client.gpd.invoker.ApiClient gpdClient;
    @MockBean
    private it.gov.pagopa.gen.wispconverter.client.checkout.invoker.ApiClient checkoutClient;
    @MockBean
    private it.gov.pagopa.gen.wispconverter.client.cache.invoker.ApiClient cacheClient;
    @MockBean
    private it.gov.pagopa.gen.wispconverter.client.decouplercaching.invoker.ApiClient decouplerCachingClient;
    @MockBean
    private PaaInviaRTSenderService paaInviaRTSenderService;
    @MockBean
    private ServiceBusService serviceBusService;
    @MockBean
    private ServiceBusSenderClient serviceBusSenderClient;
    @MockBean
    private RestClient.Builder restClientBuilder;
    @MockBean
    private CosmosClientBuilder cosmosClientBuilder;
    @Qualifier("redisSimpleTemplate")
    @MockBean
    private RedisTemplate<String, Object> redisSimpleTemplate;
    @MockBean
    private ReEventRepository reEventRepository;
    @MockBean
    private CacheRepository cacheRepository;
    @MockBean
    private ReceiptTimerService receiptTimerService;

    private String getPaSendRTPayload() {
        return TestUtils.loadFileContent("/requests/paSendRTV2.xml");
    }

    private void setConfigCacheStoredData(String servicePath, int primitiveVersion) {
        StationCreditorInstitutionDto stationCreditorInstitutionDto = new StationCreditorInstitutionDto();
        stationCreditorInstitutionDto.setCreditorInstitutionCode("{pa}");
        stationCreditorInstitutionDto.setSegregationCode(48L);
        stationCreditorInstitutionDto.setStationCode(STATION_ID);
        ReflectionTestUtils.setField(configCacheService, "configData", TestUtils.configDataCreditorInstitutionStations(stationCreditorInstitutionDto, servicePath, primitiveVersion));
    }

    @Test
    @SneakyThrows
    void sendOkReceipt() {

        // mocking cached configuration
        setConfigCacheStoredData("/creditorinstitution/station", 2);

        // mocking decoupler cached keys
        when(cacheRepository.read(anyString(), any())).thenReturn("wisp_nav2iuv_123456IUVMOCK1");

        // mocking RPT retrieve
        RPTRequestEntity rptRequestEntity = RPTRequestEntity.builder()
                .primitive("nodoInviaRPT")
                .payload(TestUtils.zipAndEncode(TestUtils.getRptPayload(false, STATION_ID, "100.00", "datispec")))
                .build();
        when(rptRequestRepository.findById(anyString())).thenReturn(Optional.of(rptRequestEntity));


        // executing request
        mvc.perform(MockMvcRequestBuilders.post("/receipt/ok")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReceiptRequest(getPaSendRTPayload()))))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful())
                .andDo(result -> {
                    assertNotNull(result);
                    assertNotNull(result.getResponse());
                });
    }

    @Test
    @SneakyThrows
    void sendOkReceipt_gpdStation() {

        // mocking cached configuration
        setConfigCacheStoredData("/gpd-payments/api/v1", 2);

        // mocking decoupler cached keys
        when(cacheRepository.read(anyString(), any())).thenReturn("wisp_nav2iuv_123456IUVMOCK1");

        // mocking RPT retrieve
        RPTRequestEntity rptRequestEntity = RPTRequestEntity.builder()
                .primitive("nodoInviaRPT")
                .payload(TestUtils.zipAndEncode(TestUtils.getRptPayload(false, STATION_ID, "100.00", "datispec")))
                .build();
        when(rptRequestRepository.findById(anyString())).thenReturn(Optional.of(rptRequestEntity));


        // executing request
        mvc.perform(MockMvcRequestBuilders.post("/receipt/ok")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReceiptRequest(getPaSendRTPayload()))))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful())
                .andDo(result -> {
                    assertNotNull(result);
                    assertNotNull(result.getResponse());
                });
    }

    @ParameterizedTest
    @CsvSource(value = {"/gpd-payments/api/v1,1", "NULL,2"}, nullValues = {"NULL"})
    @SneakyThrows
    void sendOkReceipt_misconfiguredGpdStation(String path, String version) {

        // mocking cached configuration
        setConfigCacheStoredData(path, Integer.parseInt(version));

        // mocking decoupler cached keys
        when(cacheRepository.read(anyString(), any())).thenReturn("wisp_nav2iuv_123456IUVMOCK1");

        // mocking RPT retrieve
        RPTRequestEntity rptRequestEntity = RPTRequestEntity.builder()
                .primitive("nodoInviaRPT")
                .payload(TestUtils.zipAndEncode(TestUtils.getRptPayload(false, STATION_ID, "100.00", "datispec")))
                .build();
        when(rptRequestRepository.findById(anyString())).thenReturn(Optional.of(rptRequestEntity));


        // executing request
        mvc.perform(MockMvcRequestBuilders.post("/receipt/ok")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReceiptRequest(getPaSendRTPayload()))))
                .andExpect(MockMvcResultMatchers.status().is4xxClientError())
                .andDo(result -> {
                    assertNotNull(result);
                    assertNotNull(result.getResponse());
                });
    }

    @Test
    void sendOkReceipt_notSent() throws Exception {

        // mocking cached configuration
        setConfigCacheStoredData("/creditorinstitution/station", 2);

        // mocking decoupler cached keys
        when(cacheRepository.read(anyString(), any())).thenReturn("wisp_nav2iuv_123456IUVMOCK1");

        // mocking RPT retrieve
        RPTRequestEntity rptRequestEntity = RPTRequestEntity.builder()
                .primitive("nodoInviaRPT")
                .payload(TestUtils.zipAndEncode(TestUtils.getRptPayload(false, STATION_ID, "100.00", "datispec")))
                .build();
        when(rptRequestRepository.findById(anyString())).thenReturn(Optional.of(rptRequestEntity));

        // mocking error response from creditor institution
        doThrow(new AppException(AppErrorCodeMessageEnum.RECEIPT_GENERATION_ERROR_RESPONSE_FROM_CREDITOR_INSTITUTION, "PAA_ERRORE_RESPONSE", "PAA_ERRORE_RESPONSE", "Errore PA"))
                .when(paaInviaRTSenderService).sendToCreditorInstitution(anyString(), any(), anyString());

        mvc.perform(MockMvcRequestBuilders.post("/receipt/ok")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReceiptRequest(getPaSendRTPayload()))))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful())
                .andDo(
                        result -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                        });

        verify(paaInviaRTSenderService, times(1)).sendToCreditorInstitution(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @CsvSource(value = {"<soapenv:Envelope><soapenv:Body>", "NULL"}, nullValues = {"NULL"})
    @SneakyThrows
    void sendOkReceipt_malformedRequest(String request) {

        // mocking cached configuration
        setConfigCacheStoredData("/creditorinstitution/station", 2);

        // mocking decoupler cached keys
        when(cacheRepository.read(anyString(), any())).thenReturn("wisp_nav2iuv_123456IUVMOCK1");

        // mocking RPT retrieve
        RPTRequestEntity rptRequestEntity = RPTRequestEntity.builder()
                .primitive("nodoInviaRPT")
                .payload(TestUtils.zipAndEncode(TestUtils.getRptPayload(false, STATION_ID, "100.00", "datispec")))
                .build();
        when(rptRequestRepository.findById(anyString())).thenReturn(Optional.of(rptRequestEntity));


        // executing request
        mvc.perform(MockMvcRequestBuilders.post("/receipt/ok")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReceiptRequest(request))))
                .andExpect(MockMvcResultMatchers.status().is4xxClientError())
                .andDo(result -> {
                    assertNotNull(result);
                    assertNotNull(result.getResponse());
                });
    }


    @Test
    @SneakyThrows
    void sendKoReceipt() {

        // mocking cached configuration
        setConfigCacheStoredData("/creditorinstitution/station", 2);

        // mocking decoupler cached keys
        when(cacheRepository.read(anyString(), any())).thenReturn("wisp_nav2iuv_123456IUVMOCK1");

        // mocking RPT retrieve
        RPTRequestEntity rptRequestEntity = RPTRequestEntity.builder()
                .primitive("nodoInviaRPT")
                .payload(TestUtils.zipAndEncode(TestUtils.getRptPayload(false, STATION_ID, "100.00", "datispec")))
                .build();
        when(rptRequestRepository.findById(anyString())).thenReturn(Optional.of(rptRequestEntity));


        // executing request
        List<ReceiptDto> receipts = List.of(ReceiptDto.builder()
                .fiscalCode("{pa}")
                .noticeNumber("3480000000000000")
                .paymentToken("token01")
                .build());
        mvc.perform(MockMvcRequestBuilders.post("/receipt/ko")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReceiptRequest(receipts.toString()))))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful())
                .andDo(result -> {
                    assertNotNull(result);
                    assertNotNull(result.getResponse());
                });
    }

    @Test
    @SneakyThrows
    void sendKoReceipt_gpdStation() {

        // mocking cached configuration
        setConfigCacheStoredData("/gpd-payments/api/v1", 2);

        // mocking decoupler cached keys
        when(cacheRepository.read(anyString(), any())).thenReturn("wisp_nav2iuv_123456IUVMOCK1");

        // mocking RPT retrieve
        RPTRequestEntity rptRequestEntity = RPTRequestEntity.builder()
                .primitive("nodoInviaRPT")
                .payload(TestUtils.zipAndEncode(TestUtils.getRptPayload(false, STATION_ID, "100.00", "datispec")))
                .build();
        when(rptRequestRepository.findById(anyString())).thenReturn(Optional.of(rptRequestEntity));


        // executing request
        List<ReceiptDto> receipts = List.of(ReceiptDto.builder()
                .fiscalCode("{pa}")
                .noticeNumber("3480000000000000")
                .paymentToken("token01")
                .build());
        mvc.perform(MockMvcRequestBuilders.post("/receipt/ko")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReceiptRequest(receipts.toString()))))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful())
                .andDo(result -> {
                    assertNotNull(result);
                    assertNotNull(result.getResponse());
                });
    }

    @ParameterizedTest
    @CsvSource(value = {"/gpd-payments/api/v1,1", "NULL,2"}, nullValues = {"NULL"})
    @SneakyThrows
    void sendKoReceipt_misconfiguredGpdStation(String path, String version) {

        // mocking cached configuration
        setConfigCacheStoredData(path, Integer.parseInt(version));

        // mocking decoupler cached keys
        when(cacheRepository.read(anyString(), any())).thenReturn("wisp_nav2iuv_123456IUVMOCK1");

        // mocking RPT retrieve
        RPTRequestEntity rptRequestEntity = RPTRequestEntity.builder()
                .primitive("nodoInviaRPT")
                .payload(TestUtils.zipAndEncode(TestUtils.getRptPayload(false, STATION_ID, "100.00", "datispec")))
                .build();
        when(rptRequestRepository.findById(anyString())).thenReturn(Optional.of(rptRequestEntity));


        // executing request
        List<ReceiptDto> receipts = List.of(ReceiptDto.builder()
                .fiscalCode("{pa}")
                .noticeNumber("3480000000000000")
                .paymentToken("token01")
                .build());
        mvc.perform(MockMvcRequestBuilders.post("/receipt/ko")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReceiptRequest(receipts.toString()))))
                .andExpect(MockMvcResultMatchers.status().is4xxClientError())
                .andDo(result -> {
                    assertNotNull(result);
                    assertNotNull(result.getResponse());
                });
    }

    @ParameterizedTest
    @CsvSource(value = {"[{\"paymentToken\": \"fake\",\"noticeNumber\": \"fake\"}", "NULL"}, nullValues = {"NULL"})
    @SneakyThrows
    void sendKoReceipt_malformedRequest(String request) {

        // mocking cached configuration
        setConfigCacheStoredData("/creditorinstitution/station", 2);

        // mocking decoupler cached keys
        when(cacheRepository.read(anyString(), any())).thenReturn("wisp_nav2iuv_123456IUVMOCK1");

        // mocking RPT retrieve
        RPTRequestEntity rptRequestEntity = RPTRequestEntity.builder()
                .primitive("nodoInviaRPT")
                .payload(TestUtils.zipAndEncode(TestUtils.getRptPayload(false, STATION_ID, "100.00", "datispec")))
                .build();
        when(rptRequestRepository.findById(anyString())).thenReturn(Optional.of(rptRequestEntity));


        // executing request
        mvc.perform(MockMvcRequestBuilders.post("/receipt/ko")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReceiptRequest(request))))
                .andExpect(MockMvcResultMatchers.status().is4xxClientError())
                .andDo(result -> {
                    assertNotNull(result);
                    assertNotNull(result.getResponse());
                });
    }


    @Test
    void sendKoReceipt_notSent() throws Exception {

        // mocking cached configuration
        setConfigCacheStoredData("/creditorinstitution/station", 2);

        // mocking decoupler cached keys
        when(cacheRepository.read(anyString(), any())).thenReturn("wisp_nav2iuv_123456IUVMOCK1");

        // mocking RPT retrieve
        RPTRequestEntity rptRequestEntity = RPTRequestEntity.builder()
                .primitive("nodoInviaRPT")
                .payload(TestUtils.zipAndEncode(TestUtils.getRptPayload(false, STATION_ID, "100.00", "datispec")))
                .build();
        when(rptRequestRepository.findById(anyString())).thenReturn(Optional.of(rptRequestEntity));

        // mocking error response from creditor institution
        List<ReceiptDto> receipts = List.of(ReceiptDto.builder()
                .fiscalCode("{pa}")
                .noticeNumber("3480000000000000")
                .paymentToken("token01")
                .build());
        doThrow(new AppException(AppErrorCodeMessageEnum.RECEIPT_GENERATION_ERROR_RESPONSE_FROM_CREDITOR_INSTITUTION, "PAA_ERRORE_RESPONSE", "PAA_ERRORE_RESPONSE", "Errore PA"))
                .when(paaInviaRTSenderService).sendToCreditorInstitution(anyString(), any(), anyString());

        mvc.perform(MockMvcRequestBuilders.post("/receipt/ko")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReceiptRequest(receipts.toString()))))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful())
                .andDo(
                        result -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                        });

        verify(paaInviaRTSenderService, times(1)).sendToCreditorInstitution(anyString(), any(), anyString());
    }
}
