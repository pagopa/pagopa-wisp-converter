package it.gov.pagopa.wispconverter;

import static it.gov.pagopa.wispconverter.ConstantsTestHelper.REDIRECT_PATH;
import static it.gov.pagopa.wispconverter.utils.TestUtils.getPaymentPositionModelBaseResponseDto;
import static it.gov.pagopa.wispconverter.utils.TestUtils.getPaymentPositionModelDto;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.gen.wispconverter.client.cache.model.StationCreditorInstitutionDto;
import it.gov.pagopa.gen.wispconverter.client.iuvgenerator.model.IUVGenerationResponseDto;
import it.gov.pagopa.gen.wispconverter.client.gpd.model.MultiplePaymentPositionModelDto;
import it.gov.pagopa.gen.wispconverter.client.gpd.model.PaymentPositionModelDto;
import it.gov.pagopa.wispconverter.repository.RPTRequestRepository;
import it.gov.pagopa.wispconverter.repository.RTRequestRepository;
import it.gov.pagopa.wispconverter.repository.ReEventRepository;
import it.gov.pagopa.wispconverter.repository.model.RPTRequestEntity;
import it.gov.pagopa.wispconverter.service.ConfigCacheService;
import it.gov.pagopa.wispconverter.service.model.re.ReEventDto;
import it.gov.pagopa.wispconverter.utils.TestUtils;

import java.net.URI;
import java.util.*;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.web.client.RestClientException;

@ActiveProfiles(profiles = "test")
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class RptTest {


    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    private MockMvc mvc;
    @Autowired private ConfigCacheService configCacheService;
    @MockBean
    private RPTRequestRepository rptRequestRepository;

    @MockBean
    private RTRequestRepository rtRequestRepository;
    @MockBean private it.gov.pagopa.gen.wispconverter.client.iuvgenerator.invoker.ApiClient iuveneratorClient;
    @MockBean private it.gov.pagopa.gen.wispconverter.client.gpd.invoker.ApiClient gpdClient;
    @MockBean private it.gov.pagopa.gen.wispconverter.client.checkout.invoker.ApiClient checkoutClient;
    @MockBean private it.gov.pagopa.gen.wispconverter.client.cache.invoker.ApiClient cacheClient;
    @MockBean private it.gov.pagopa.gen.wispconverter.client.decouplercaching.invoker.ApiClient decouplerCachingClient;
    @Qualifier("redisSimpleTemplate")
    @MockBean private RedisTemplate<String, Object> redisSimpleTemplate;
    @MockBean
    private ReEventRepository reEventRepository;
    @MockBean
    private ServiceBusSenderClient serviceBusSenderClient;

    @Test
    void success_paymentPositionUpdate() throws Exception {
        String station = "mystation";
        StationCreditorInstitutionDto stationCreditorInstitutionDto = new StationCreditorInstitutionDto();
        stationCreditorInstitutionDto.setCreditorInstitutionCode("{pa}");
        stationCreditorInstitutionDto.setSegregationCode(12L);
        stationCreditorInstitutionDto.setStationCode(station);
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData",TestUtils.configDataCreditorInstitutionStations(stationCreditorInstitutionDto));
        HttpHeaders headers = new HttpHeaders();
        headers.add("location","locationheader");
        it.gov.pagopa.gen.wispconverter.client.checkout.model.CartResponseDto cartResponseDto = new it.gov.pagopa.gen.wispconverter.client.checkout.model.CartResponseDto();
        cartResponseDto.setCheckoutRedirectUrl(URI.create("http://www.google.com"));
        TestUtils.setMock(checkoutClient, ResponseEntity.status(HttpStatus.FOUND).headers(headers).body(cartResponseDto));
        TestUtils.setMockGet(gpdClient,ResponseEntity.ok().body(getPaymentPositionModelBaseResponseDto()));
        TestUtils.setMockPut(gpdClient,ResponseEntity.ok().body(getPaymentPositionModelDto()));
        TestUtils.setMock(decouplerCachingClient,ResponseEntity.ok().build());
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getRptPayload(false,station,"100.00",null))
                                ).build()
                )
        );
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));

        mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?sessionId=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is(HttpStatus.FOUND.value()))
                .andDo(
                        (result) -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                        });

        verify(checkoutClient,times(1)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(gpdClient,times(2)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(decouplerCachingClient,times(1)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());

        ArgumentCaptor<Object> argument = ArgumentCaptor.forClass(Object.class);
        verify(gpdClient, times(2)).invokeAPI(any(),any(),any(),any(),argument.capture(),any(),any(),any(),any(),any(),any(),any());
        MultiplePaymentPositionModelDto value = new MultiplePaymentPositionModelDto();
        value.setPaymentPositions(List.of((PaymentPositionModelDto) argument.getValue()));
        assertEquals(1, value.getPaymentPositions().size());
        assertEquals("TTTTTT11T11T123T", value.getPaymentPositions().get(0).getFiscalCode());

        ArgumentCaptor<ReEventDto> reevents = ArgumentCaptor.forClass(ReEventDto.class);
        verify(reEventRepository,times(7)).save(any());
        reevents.getAllValues();
    }

    @Test
    void success_tassonomia() throws Exception {
        String station = "mystation";
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData",TestUtils.configData(station));
        HttpHeaders headers = new HttpHeaders();
        headers.add("location","locationheader");
        TestUtils.setMock(checkoutClient, ResponseEntity.status(HttpStatus.FOUND).headers(headers).build());

        IUVGenerationResponseDto iuvGenerationModelResponseDto = new IUVGenerationResponseDto();
        iuvGenerationModelResponseDto.setIuv("00000000");
        TestUtils.setMock(iuveneratorClient,ResponseEntity.ok().body(iuvGenerationModelResponseDto));
        TestUtils.setMock(gpdClient,ResponseEntity.ok().build());
        TestUtils.setMock(decouplerCachingClient,ResponseEntity.ok().build());
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getRptPayload(false,station,"100.00","datispec"))
                                ).build()
                )
        );
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));



        mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?sessionId=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful())
                .andDo(
                        (result) -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                        });

        verify(checkoutClient,times(1)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(iuveneratorClient,times(1)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(gpdClient,times(1)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(decouplerCachingClient,times(1)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test
    void success_bollo() throws Exception {
        String station = "mystation";
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData",TestUtils.configData(station));
        HttpHeaders headers = new HttpHeaders();
        headers.add("location","locationheader");
        TestUtils.setMock(checkoutClient, ResponseEntity.status(HttpStatus.FOUND).headers(headers).build());

        IUVGenerationResponseDto iuvGenerationModelResponseDto = new IUVGenerationResponseDto();
        iuvGenerationModelResponseDto.setIuv("00000000");
        TestUtils.setMock(iuveneratorClient,ResponseEntity.ok().body(iuvGenerationModelResponseDto));
        TestUtils.setMock(gpdClient,ResponseEntity.ok().build());
        TestUtils.setMock(decouplerCachingClient,ResponseEntity.ok().build());
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getRptPayload(true,station,"100.00",null))
                                ).build()
                )
        );
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));



        mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?sessionId=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful())
                .andDo(
                        (result) -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                        });

        verify(checkoutClient,times(1)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(iuveneratorClient,times(1)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(gpdClient,times(1)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(decouplerCachingClient,times(1)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test
    void fail_rpt_not_exists() throws Exception {
        String station = "mystation";
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData",TestUtils.configData(station));
        HttpHeaders headers = new HttpHeaders();
        headers.add("location","locationheader");
        TestUtils.setMock(checkoutClient, ResponseEntity.status(HttpStatus.FOUND).headers(headers).build());

        IUVGenerationResponseDto iuvGenerationModelResponseDto = new IUVGenerationResponseDto();
        iuvGenerationModelResponseDto.setIuv("00000000");
        doThrow(new RestClientException("fail", new RuntimeException("this test must fail")))
                .when(iuveneratorClient).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        TestUtils.setMock(gpdClient,ResponseEntity.ok().build());
        TestUtils.setMock(decouplerCachingClient,ResponseEntity.ok().build());
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));


        MvcResult resultActions = mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?sessionId=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful()).andDo(
                        (result) -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                        }).andReturn();
        assertEquals("text/html;charset=UTF-8",resultActions.getResponse().getContentType());
        String contentAsString = resultActions.getResponse().getContentAsString();
        assertTrue(contentAsString.contains("Riprova, oppure contatta l'assistenza"));
        verify(checkoutClient,times(0)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(gpdClient,times(0)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(decouplerCachingClient,times(0)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test
    void fail_generic() throws Exception {
        String station = "mystation";
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData",TestUtils.configData(station));
        HttpHeaders headers = new HttpHeaders();
        headers.add("location","locationheader");
        TestUtils.setMock(checkoutClient, ResponseEntity.status(HttpStatus.FOUND).headers(headers).build());

        IUVGenerationResponseDto iuvGenerationModelResponseDto = new IUVGenerationResponseDto();
        iuvGenerationModelResponseDto.setIuv("00000000");
        doThrow(new RestClientException("fail", new RuntimeException("this test must fail")))
                .when(iuveneratorClient).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        TestUtils.setMock(gpdClient,ResponseEntity.ok().build());
        TestUtils.setMock(decouplerCachingClient,ResponseEntity.ok().build());
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(rptRequestRepository.findById(any())).thenThrow(new RuntimeException("fail"));

        MvcResult resultActions = mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?sessionId=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful()).andDo(
                        (result) -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                        }).andReturn();
        assertEquals("text/html;charset=UTF-8",resultActions.getResponse().getContentType());
        String contentAsString = resultActions.getResponse().getContentAsString();
        assertTrue(contentAsString.contains("Riprova, oppure contatta l'assistenza"));
        verify(checkoutClient,times(0)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(gpdClient,times(0)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(decouplerCachingClient,times(0)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test
    void fail_getNAVCodeFromIUVGenerator() throws Exception {
        String station = "mystation";
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData",TestUtils.configData(station));
        HttpHeaders headers = new HttpHeaders();
        headers.add("location","locationheader");
        TestUtils.setMock(checkoutClient, ResponseEntity.status(HttpStatus.FOUND).headers(headers).build());

        IUVGenerationResponseDto iuvGenerationModelResponseDto = new IUVGenerationResponseDto();
        iuvGenerationModelResponseDto.setIuv("00000000");
        doThrow(new RestClientException("fail", new RuntimeException("this test must fail")))
                .when(iuveneratorClient).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        TestUtils.setMock(gpdClient,ResponseEntity.ok().build());
        TestUtils.setMock(decouplerCachingClient,ResponseEntity.ok().build());
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getRptPayload(false,station,"100.00",null))
                                ).build()
                )
        );
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));


        mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?sessionId=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful());

        verify(checkoutClient,times(0)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(gpdClient,times(0)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(decouplerCachingClient,times(0)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test
    void fail_createDebtPositions() throws Exception {
        String station = "mystation";
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData",TestUtils.configData(station));
        HttpHeaders headers = new HttpHeaders();
        headers.add("location","locationheader");
        TestUtils.setMock(checkoutClient, ResponseEntity.status(HttpStatus.FOUND).headers(headers).build());

        IUVGenerationResponseDto iuvGenerationModelResponseDto = new IUVGenerationResponseDto();
        iuvGenerationModelResponseDto.setIuv("00000000");
        TestUtils.setMock(iuveneratorClient,ResponseEntity.ok().body(iuvGenerationModelResponseDto));
        doThrow(new RestClientException("fail", new RuntimeException("this test must fail")))
                .when(gpdClient).parameterToMultiValueMap(any(),any(),any());
        TestUtils.setMock(decouplerCachingClient,ResponseEntity.ok().build());
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getRptPayload(false,station,"100.00",null))
                                ).build()
                )
        );
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));



        mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?sessionId=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful());

        verify(iuveneratorClient,times(1)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(gpdClient,times(0)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(decouplerCachingClient,times(0)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(checkoutClient,times(0)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test
    void fail_storeRequestMappingInCache() throws Exception {
        String station = "mystation";
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData",TestUtils.configData(station));
        HttpHeaders headers = new HttpHeaders();
        headers.add("location","locationheader");
        TestUtils.setMock(checkoutClient, ResponseEntity.status(HttpStatus.FOUND).headers(headers).build());

        IUVGenerationResponseDto iuvGenerationModelResponseDto = new IUVGenerationResponseDto();
        iuvGenerationModelResponseDto.setIuv("00000000");
        TestUtils.setMock(iuveneratorClient,ResponseEntity.ok().body(iuvGenerationModelResponseDto));
        TestUtils.setMock(gpdClient,ResponseEntity.ok().build());
    doThrow(new RestClientException("fail", new RuntimeException("this test must fail")))
        .when(decouplerCachingClient)
        .invokeAPI(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getRptPayload(false,station,"100.00",null))
                                ).build()
                )
        );
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));

        mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?sessionId=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful());

        verify(iuveneratorClient,times(1)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(gpdClient,times(1)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(checkoutClient,times(0)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());

    }


    @Test
    void fail_checkout() throws Exception {
        String station = "mystation";
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData",TestUtils.configData(station));
        HttpHeaders headers = new HttpHeaders();
        headers.add("location","locationheader");
        doThrow(new RestClientException("fail", new RuntimeException("this test must fail")))
                .when(checkoutClient).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());

        IUVGenerationResponseDto iuvGenerationModelResponseDto = new IUVGenerationResponseDto();
        iuvGenerationModelResponseDto.setIuv("00000000");
        TestUtils.setMock(iuveneratorClient,ResponseEntity.ok().body(iuvGenerationModelResponseDto));
        TestUtils.setMock(gpdClient,ResponseEntity.ok().build());
        TestUtils.setMock(decouplerCachingClient,ResponseEntity.ok().build());
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getRptPayload(false,station,"100.00",null))
                                ).build()
                )
        );
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));



        mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?sessionId=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful());

        verify(iuveneratorClient,times(1)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(gpdClient,times(1)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(decouplerCachingClient,times(1)).invokeAPI(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());

    }

}
