package it.gov.pagopa.wispconverter.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.gen.wispconverter.client.cache.model.StationCreditorInstitutionDto;
import it.gov.pagopa.gen.wispconverter.client.gpd.model.MultiplePaymentPositionModelDto;
import it.gov.pagopa.gen.wispconverter.client.gpd.model.PaymentPositionModelDto;
import it.gov.pagopa.gen.wispconverter.client.iuvgenerator.model.IUVGenerationResponseDto;
import it.gov.pagopa.wispconverter.Application;
import it.gov.pagopa.wispconverter.repository.CacheRepository;
import it.gov.pagopa.wispconverter.repository.RPTRequestRepository;
import it.gov.pagopa.wispconverter.repository.model.RPTRequestEntity;
import it.gov.pagopa.wispconverter.service.ConfigCacheService;
import it.gov.pagopa.wispconverter.service.PaaInviaRTSenderService;
import it.gov.pagopa.wispconverter.service.ReceiptService;
import it.gov.pagopa.wispconverter.service.RecoveryService;
import it.gov.pagopa.wispconverter.service.model.re.ReEventDto;
import it.gov.pagopa.wispconverter.utils.TestUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static it.gov.pagopa.wispconverter.utils.ConstantsTestHelper.REDIRECT_PATH;
import static it.gov.pagopa.wispconverter.utils.TestUtils.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@ActiveProfiles(profiles = "test")
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class RptTest {

    private static final String STATION_ID = "mystation";

    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ConfigCacheService configCacheService;
    @MockBean
    private RPTRequestRepository rptRequestRepository;

    @MockBean
    private it.gov.pagopa.gen.wispconverter.client.iuvgenerator.invoker.ApiClient iuvGeneratorClient;
    @MockBean
    private it.gov.pagopa.gen.wispconverter.client.gpd.invoker.ApiClient gpdClient;
    @MockBean
    private it.gov.pagopa.gen.wispconverter.client.checkout.invoker.ApiClient checkoutClient;
    @MockBean
    private it.gov.pagopa.gen.wispconverter.client.cache.invoker.ApiClient cacheClient;
    @MockBean
    private it.gov.pagopa.gen.wispconverter.client.decouplercaching.invoker.ApiClient decouplerCachingClient;
    @Qualifier("redisSimpleTemplate")
    @MockBean
    private RedisTemplate<String, Object> redisSimpleTemplate;
    @MockBean
    private CacheRepository cacheRepository;
    @MockBean
    private ReceiptService receiptService;
    @MockBean
    private RecoveryService recoveryService;
    @MockBean
    private PaaInviaRTSenderService paaInviaRTSenderService;

    private void setConfigCacheStoredData(String servicePath, int primitiveVersion) {
        StationCreditorInstitutionDto stationCreditorInstitutionDto = new StationCreditorInstitutionDto();
        stationCreditorInstitutionDto.setCreditorInstitutionCode("pa");
        stationCreditorInstitutionDto.setSegregationCode(48L);
        stationCreditorInstitutionDto.setStationCode(STATION_ID);
        ReflectionTestUtils.setField(configCacheService, "configData", TestUtils.configDataCreditorInstitutionStations(stationCreditorInstitutionDto, servicePath, primitiveVersion));
    }

    @Test
    void success_debtPositionUpdateValid() throws Exception {
        /*String station = "mystation";
        StationCreditorInstitutionDto stationCreditorInstitutionDto = new StationCreditorInstitutionDto();
        stationCreditorInstitutionDto.setCreditorInstitutionCode("pa");
        stationCreditorInstitutionDto.setSegregationCode(12L);
        stationCreditorInstitutionDto.setStationCode(station);
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData", TestUtils.configDataCreditorInstitutionStations(stationCreditorInstitutionDto));*/

        // mocking cached configuration
        setConfigCacheStoredData("/gpd-payments/api/v1", 2);

        HttpHeaders headers = new HttpHeaders();
        headers.add("location", "locationheader");
        it.gov.pagopa.gen.wispconverter.client.checkout.model.CartResponseDto cartResponseDto = new it.gov.pagopa.gen.wispconverter.client.checkout.model.CartResponseDto();
        cartResponseDto.setCheckoutRedirectUrl(URI.create("http://www.google.com"));
        TestUtils.setMock(checkoutClient, ResponseEntity.status(HttpStatus.FOUND).headers(headers).body(cartResponseDto));
        TestUtils.setMockGet(gpdClient, ResponseEntity.ok().body(getValidPaymentPositionModelBaseResponseDto("48")));
        TestUtils.setMockPut(gpdClient, ResponseEntity.ok().body(getPaymentPositionModelDto()));
        TestUtils.setMock(decouplerCachingClient, ResponseEntity.ok().build());
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getRptPayload(false, STATION_ID, "pa", "483456IUVMOCK1", "100.00", null))
                                ).build()
                )
        );
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));

        mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?idSession=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is(HttpStatus.FOUND.value()))
                .andDo(
                        result -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                        });

        verify(checkoutClient, times(1)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gpdClient, times(2)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(decouplerCachingClient, times(2)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        ArgumentCaptor<Object> argument = ArgumentCaptor.forClass(Object.class);
        verify(gpdClient, times(2)).invokeAPI(any(), any(), any(), any(), argument.capture(), any(), any(), any(), any(), any(), any(), any());
        MultiplePaymentPositionModelDto value = new MultiplePaymentPositionModelDto();
        value.setPaymentPositions(List.of((PaymentPositionModelDto) argument.getValue()));
        assertEquals(1, value.getPaymentPositions().size());
        assertEquals("TTTTTT11T11T123T", value.getPaymentPositions().get(0).getFiscalCode());

        ArgumentCaptor<ReEventDto> reevents = ArgumentCaptor.forClass(ReEventDto.class);
        reevents.getAllValues();
    }

    @Test
    void success_debtPositionUpdateInvalid() throws Exception {
        String station = "mystation";
        StationCreditorInstitutionDto stationCreditorInstitutionDto = new StationCreditorInstitutionDto();
        stationCreditorInstitutionDto.setCreditorInstitutionCode("pa");
        stationCreditorInstitutionDto.setSegregationCode(12L);
        stationCreditorInstitutionDto.setStationCode(station);
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData", TestUtils.configDataCreditorInstitutionStations(stationCreditorInstitutionDto));
        HttpHeaders headers = new HttpHeaders();
        headers.add("location", "locationheader");
        it.gov.pagopa.gen.wispconverter.client.checkout.model.CartResponseDto cartResponseDto = new it.gov.pagopa.gen.wispconverter.client.checkout.model.CartResponseDto();
        cartResponseDto.setCheckoutRedirectUrl(URI.create("http://www.google.com"));
        TestUtils.setMock(checkoutClient, ResponseEntity.status(HttpStatus.FOUND).headers(headers).body(cartResponseDto));
        TestUtils.setMockGet(gpdClient, ResponseEntity.ok().body(getInvalidPaymentPositionModelBaseResponseDto()));
        TestUtils.setMockPut(gpdClient, ResponseEntity.ok().body(getPaymentPositionModelDto()));
        TestUtils.setMock(decouplerCachingClient, ResponseEntity.ok().build());
        when(rptRequestRepository.findById(any()))
                .thenReturn(Optional.of(
                                RPTRequestEntity.builder()
                                        .primitive("nodoInviaRPT")
                                        .payload(TestUtils.zipAndEncode(TestUtils.getRptPayload(false, station, "pa", "348456IUVMOCK1", "100.00", null)))
                                        .build()
                        )
                );
        when(cacheRepository.read(any(), any())).thenReturn("wisp_nav2iuv_dominio");
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));

        mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?idSession=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful())
                .andDo(
                        result -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                        });

        verify(gpdClient, times(2)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(decouplerCachingClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(checkoutClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        ArgumentCaptor<ReEventDto> reevents = ArgumentCaptor.forClass(ReEventDto.class);
        reevents.getAllValues();
    }

    @Test
    void success_debtPositionCreate() throws Exception {
        String station = "mystation";
        StationCreditorInstitutionDto stationCreditorInstitutionDto = new StationCreditorInstitutionDto();
        stationCreditorInstitutionDto.setCreditorInstitutionCode("pa");
        stationCreditorInstitutionDto.setSegregationCode(12L);
        stationCreditorInstitutionDto.setStationCode(station);
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData", TestUtils.configDataCreditorInstitutionStations(stationCreditorInstitutionDto));
        HttpHeaders headers = new HttpHeaders();
        headers.add("location", "locationheader");
        it.gov.pagopa.gen.wispconverter.client.checkout.model.CartResponseDto cartResponseDto = new it.gov.pagopa.gen.wispconverter.client.checkout.model.CartResponseDto();
        cartResponseDto.setCheckoutRedirectUrl(URI.create("http://www.google.com"));
        TestUtils.setMock(checkoutClient, ResponseEntity.status(HttpStatus.FOUND).headers(headers).body(cartResponseDto));

        IUVGenerationResponseDto iuvGenerationModelResponseDto = new IUVGenerationResponseDto();
        iuvGenerationModelResponseDto.setIuv("123456IUVMOCK2");
        TestUtils.setMock(iuvGeneratorClient, ResponseEntity.ok().body(iuvGenerationModelResponseDto));
        TestUtils.setMockGetExceptionNotFound(gpdClient);
        TestUtils.setMockPost(gpdClient, ResponseEntity.ok().body(getPaymentPositionModelDto()));
        TestUtils.setMock(decouplerCachingClient, ResponseEntity.ok().build());
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getRptPayload(false, station, "pa", "123456IUVMOCK1", "100.00", "datispec"))
                                ).build()
                )
        );
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));

        mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?idSession=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is(HttpStatus.FOUND.value()))
                .andDo(
                        result -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                        });

        verify(checkoutClient, times(1)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(iuvGeneratorClient, times(1)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gpdClient, times(2)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(decouplerCachingClient, times(2)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void success_bollo() throws Exception {
        String station = "mystation";
        StationCreditorInstitutionDto stationCreditorInstitutionDto = new StationCreditorInstitutionDto();
        stationCreditorInstitutionDto.setCreditorInstitutionCode("pa");
        stationCreditorInstitutionDto.setSegregationCode(0L);
        stationCreditorInstitutionDto.setStationCode(station);
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData", TestUtils.configDataCreditorInstitutionStations(stationCreditorInstitutionDto));
        HttpHeaders headers = new HttpHeaders();
        headers.add("location", "locationheader");
        it.gov.pagopa.gen.wispconverter.client.checkout.model.CartResponseDto cartResponseDto = new it.gov.pagopa.gen.wispconverter.client.checkout.model.CartResponseDto();
        cartResponseDto.setCheckoutRedirectUrl(URI.create("http://www.google.com"));
        TestUtils.setMock(checkoutClient, ResponseEntity.status(HttpStatus.FOUND).headers(headers).body(cartResponseDto));

        IUVGenerationResponseDto iuvGenerationModelResponseDto = new IUVGenerationResponseDto();
        iuvGenerationModelResponseDto.setIuv("00000000");
        TestUtils.setMock(iuvGeneratorClient, ResponseEntity.ok().body(iuvGenerationModelResponseDto));
        TestUtils.setMockGetExceptionNotFound(gpdClient);
        TestUtils.setMockPost(gpdClient, ResponseEntity.ok().body(getPaymentPositionModelDto()));
        TestUtils.setMock(decouplerCachingClient, ResponseEntity.ok().build());
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getRptPayload(true, station, "pa", "123456IUVMOCK1", "100.00", null))
                                ).build()
                )
        );
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));

        mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?idSession=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is(HttpStatus.FOUND.value()))
                .andDo(
                        result -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                        });

        verify(checkoutClient, times(1)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(iuvGeneratorClient, times(1)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gpdClient, times(2)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(decouplerCachingClient, times(2)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void fail_rptNotFound() throws Exception {
        MvcResult resultActions = mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?idSession=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful()).andDo(
                        result -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                        }).andReturn();
        assertEquals("text/html;charset=UTF-8", resultActions.getResponse().getContentType());
        String contentAsString = resultActions.getResponse().getContentAsString();
        assertTrue(contentAsString.contains("Riprova, oppure contatta l'assistenza"));
        verify(checkoutClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gpdClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(decouplerCachingClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void fail_iuvGeneratorError() throws Exception {
        String station = "mystation";
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData", TestUtils.configData(station));
        HttpHeaders headers = new HttpHeaders();
        headers.add("location", "locationheader");
        TestUtils.setMock(checkoutClient, ResponseEntity.status(HttpStatus.FOUND).headers(headers).build());

        IUVGenerationResponseDto iuvGenerationModelResponseDto = new IUVGenerationResponseDto();
        iuvGenerationModelResponseDto.setIuv("00000000");
        doThrow(new RestClientException("fail", new RuntimeException("this test must fail")))
                .when(iuvGeneratorClient).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        TestUtils.setMockGetExceptionNotFound(gpdClient);
        TestUtils.setMock(decouplerCachingClient, ResponseEntity.ok().build());
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getRptPayload(false, station, "100.00", "datispec"))
                                ).build()
                )
        );

        MvcResult resultActions = mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?idSession=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful()).andDo(
                        result -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                        }).andReturn();
        assertEquals("text/html;charset=UTF-8", resultActions.getResponse().getContentType());
        String contentAsString = resultActions.getResponse().getContentAsString();
        assertTrue(contentAsString.contains("Riprova, oppure contatta l'assistenza"));
        verify(iuvGeneratorClient, times(1)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gpdClient, times(1)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(decouplerCachingClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(checkoutClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void fail_generic() throws Exception {
        String station = "mystation";
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData", TestUtils.configData(station));
        HttpHeaders headers = new HttpHeaders();
        headers.add("location", "locationheader");
        TestUtils.setMock(checkoutClient, ResponseEntity.status(HttpStatus.FOUND).headers(headers).build());

        IUVGenerationResponseDto iuvGenerationModelResponseDto = new IUVGenerationResponseDto();
        iuvGenerationModelResponseDto.setIuv("00000000");
        doThrow(new RestClientException("fail", new RuntimeException("this test must fail")))
                .when(iuvGeneratorClient).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        TestUtils.setMock(gpdClient, ResponseEntity.ok().build());
        TestUtils.setMock(decouplerCachingClient, ResponseEntity.ok().build());
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(rptRequestRepository.findById(any())).thenThrow(new RuntimeException("fail"));

        MvcResult resultActions = mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?idSession=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful()).andDo(
                        result -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                        }).andReturn();
        assertEquals("text/html;charset=UTF-8", resultActions.getResponse().getContentType());
        String contentAsString = resultActions.getResponse().getContentAsString();
        assertTrue(contentAsString.contains("Riprova, oppure contatta l'assistenza"));
        verify(checkoutClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gpdClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(decouplerCachingClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void fail_debtPositionCreate() throws Exception {
        String station = "mystation";
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData", TestUtils.configData(station));
        HttpHeaders headers = new HttpHeaders();
        headers.add("location", "locationheader");
        TestUtils.setMock(checkoutClient, ResponseEntity.status(HttpStatus.FOUND).headers(headers).build());

        IUVGenerationResponseDto iuvGenerationModelResponseDto = new IUVGenerationResponseDto();
        iuvGenerationModelResponseDto.setIuv("00000000");
        TestUtils.setMock(iuvGeneratorClient, ResponseEntity.ok().body(iuvGenerationModelResponseDto));
        TestUtils.setMockGetExceptionNotFound(gpdClient);
        doThrow(new RestClientException("fail", new RuntimeException("this test must fail")))
                .when(gpdClient).parameterToMultiValueMap(any(), any(), any());
        TestUtils.setMock(decouplerCachingClient, ResponseEntity.ok().build());
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getRptPayload(false, station, "100.00", null))
                                ).build()
                )
        );
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));

        mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?idSession=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful());

        verify(iuvGeneratorClient, times(1)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gpdClient, times(1)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(decouplerCachingClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(checkoutClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void fail_storeRequestMappingInCache() throws Exception {
        String station = "mystation";
        StationCreditorInstitutionDto stationCreditorInstitutionDto = new StationCreditorInstitutionDto();
        stationCreditorInstitutionDto.setCreditorInstitutionCode("{pa}");
        stationCreditorInstitutionDto.setSegregationCode(12L);
        stationCreditorInstitutionDto.setStationCode(station);
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData", TestUtils.configDataCreditorInstitutionStations(stationCreditorInstitutionDto));
        IUVGenerationResponseDto iuvGenerationModelResponseDto = new IUVGenerationResponseDto();
        iuvGenerationModelResponseDto.setIuv("123456IUVMOCK2");
        TestUtils.setMock(iuvGeneratorClient, ResponseEntity.ok().body(iuvGenerationModelResponseDto));
        TestUtils.setMockGetExceptionNotFound(gpdClient);
        TestUtils.setMockPost(gpdClient, ResponseEntity.ok().body(getPaymentPositionModelDto()));
        doThrow(new RestClientException("fail", new RuntimeException("this test must fail")))
                .when(decouplerCachingClient)
                .invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getRptPayload(false, station, "100.00", null))
                                ).build()
                )
        );

        mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?idSession=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful());

        verify(iuvGeneratorClient, times(1)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gpdClient, times(2)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(checkoutClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }


    @Test
    void fail_checkoutRequest() throws Exception {
        String station = "mystation";
        StationCreditorInstitutionDto stationCreditorInstitutionDto = new StationCreditorInstitutionDto();
        stationCreditorInstitutionDto.setCreditorInstitutionCode("pa");
        stationCreditorInstitutionDto.setSegregationCode(0L);
        stationCreditorInstitutionDto.setStationCode(station);
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData", TestUtils.configDataCreditorInstitutionStations(stationCreditorInstitutionDto));
        doThrow(new RestClientException("fail", new RuntimeException("this test must fail")))
                .when(checkoutClient).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        IUVGenerationResponseDto iuvGenerationModelResponseDto = new IUVGenerationResponseDto();
        iuvGenerationModelResponseDto.setIuv("00000000");
        TestUtils.setMock(iuvGeneratorClient, ResponseEntity.ok().body(iuvGenerationModelResponseDto));
        TestUtils.setMockGetExceptionNotFound(gpdClient);
        TestUtils.setMockPost(gpdClient, ResponseEntity.ok().body(getPaymentPositionModelDto()));
        TestUtils.setMock(decouplerCachingClient, ResponseEntity.ok().build());
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getRptPayload(false, station, "pa", "123456IUVMOCK1", "100.00", null))
                                ).build()
                )
        );
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));

        mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?idSession=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful());

        verify(iuvGeneratorClient, times(1)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gpdClient, times(2)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(decouplerCachingClient, times(1)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(checkoutClient, times(1)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void fail_ibanNullValue() throws Exception {
        String station = "mystation";
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData", TestUtils.configData(station));
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getRptNullIbanPayload(station, "100.00", "datispec"))
                                ).build()
                )
        );
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));

        MvcResult resultActions = mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?idSession=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful()).andDo(
                        result -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                        }).andReturn();
        assertEquals("text/html;charset=UTF-8", resultActions.getResponse().getContentType());
        String contentAsString = resultActions.getResponse().getContentAsString();
        assertTrue(contentAsString.contains("Riprova, oppure contatta l'assistenza"));

        verify(checkoutClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(iuvGeneratorClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gpdClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(decouplerCachingClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void fail_nullDebtPosition() throws Exception {
        String station = "mystation";
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData", TestUtils.configData(station));

        TestUtils.setMock(gpdClient, ResponseEntity.ok().build());
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getRptPayload(false, station, "100.00", "datispec"))
                                ).build()
                )
        );
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));

        MvcResult resultActions = mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?idSession=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful()).andDo(
                        result -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                        }).andReturn();
        assertEquals("text/html;charset=UTF-8", resultActions.getResponse().getContentType());
        String contentAsString = resultActions.getResponse().getContentAsString();
        assertTrue(contentAsString.contains("Riprova, oppure contatta l'assistenza"));

        verify(checkoutClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(iuvGeneratorClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gpdClient, times(1)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(decouplerCachingClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void fail_debtPositionBadRequest() throws Exception {
        String station = "mystation";
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData", TestUtils.configData(station));
        TestUtils.setMockGetExceptionBadRequest(gpdClient);
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getRptPayload(false, station, "100.00", "datispec"))
                                ).build()
                )
        );
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));

        MvcResult resultActions = mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?idSession=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful()).andDo(
                        result -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                        }).andReturn();
        assertEquals("text/html;charset=UTF-8", resultActions.getResponse().getContentType());
        String contentAsString = resultActions.getResponse().getContentAsString();
        assertTrue(contentAsString.contains("Riprova, oppure contatta l'assistenza"));

        verify(checkoutClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(iuvGeneratorClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gpdClient, times(1)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(decouplerCachingClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
