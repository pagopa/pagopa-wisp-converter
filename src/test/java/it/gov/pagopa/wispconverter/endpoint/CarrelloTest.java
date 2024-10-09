package it.gov.pagopa.wispconverter.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.gen.wispconverter.client.cache.model.StationCreditorInstitutionDto;
import it.gov.pagopa.gen.wispconverter.client.iuvgenerator.model.IUVGenerationResponseDto;
import it.gov.pagopa.wispconverter.Application;
import it.gov.pagopa.wispconverter.repository.RPTRequestRepository;
import it.gov.pagopa.wispconverter.repository.model.RPTRequestEntity;
import it.gov.pagopa.wispconverter.service.ConfigCacheService;
import it.gov.pagopa.wispconverter.service.PaaInviaRTSenderService;
import it.gov.pagopa.wispconverter.service.ReceiptService;
import it.gov.pagopa.wispconverter.service.RecoveryService;
import it.gov.pagopa.wispconverter.utils.TestUtils;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.net.URI;
import java.util.Optional;

import static it.gov.pagopa.wispconverter.utils.ConstantsTestHelper.REDIRECT_PATH;
import static it.gov.pagopa.wispconverter.utils.TestUtils.getPaymentPositionModelDto;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ActiveProfiles(profiles = "test")
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class CarrelloTest {

    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ConfigCacheService configCacheService;

    @MockBean
    private RPTRequestRepository rptRequestRepository;
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
    @Qualifier("redisSimpleTemplate")
    @MockBean
    private RedisTemplate<String, Object> redisSimpleTemplate;
    @MockBean
    private ReceiptService receiptService;
    @MockBean
    private RecoveryService recoveryService;
    @MockBean
    private PaaInviaRTSenderService paaInviaRTSenderService;

    @Test
    void success() throws Exception {
        String station = "mystation";
        StationCreditorInstitutionDto stationCreditorInstitutionDto = new StationCreditorInstitutionDto();
        stationCreditorInstitutionDto.setCreditorInstitutionCode("idPa_000000");
        stationCreditorInstitutionDto.setSegregationCode(0L);
        stationCreditorInstitutionDto.setStationCode(station);
        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData", TestUtils.configDataCreditorInstitutionStations(stationCreditorInstitutionDto));

        HttpHeaders headers = new HttpHeaders();
        headers.add("location", "locationheader");
        it.gov.pagopa.gen.wispconverter.client.checkout.model.CartResponseDto cartResponseDto = new it.gov.pagopa.gen.wispconverter.client.checkout.model.CartResponseDto();
        cartResponseDto.setCheckoutRedirectUrl(URI.create("http://www.google.com"));
        TestUtils.setMock(checkoutClient, ResponseEntity.status(HttpStatus.FOUND).headers(headers).body(cartResponseDto));
        it.gov.pagopa.gen.wispconverter.client.iuvgenerator.model.IUVGenerationResponseDto iuvGenerationModelResponseDto = new IUVGenerationResponseDto();
        iuvGenerationModelResponseDto.setIuv("00000000");
        TestUtils.setMock(iuveneratorClient, ResponseEntity.ok().body(iuvGenerationModelResponseDto));
        TestUtils.setMockGetExceptionNotFound(gpdClient);
        TestUtils.setMockPost(gpdClient, ResponseEntity.ok().body(getPaymentPositionModelDto()));
        TestUtils.setMock(decouplerCachingClient, ResponseEntity.ok().build());
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaCarrelloRPT")
                                .payload(
                                        TestUtils.zipAndEncode(TestUtils.getCarrelloPayload(2, station,
                                                "100.00", true, "idPa_000000"
                                        ))
                                ).build()
                )
        );
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));


        mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?idSession=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is(HttpStatus.FOUND.value()))
                .andDo(
                        result -> {
                            Assert.assertNotNull(result);
                            Assert.assertNotNull(result.getResponse());
                        });

        verify(checkoutClient, times(1)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(iuveneratorClient, times(1)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gpdClient, times(2)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(decouplerCachingClient, times(2)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void fail_multibeneficiario_less_rpts() throws Exception {
        String station = "mystation";

        org.springframework.test.util.ReflectionTestUtils.setField(configCacheService, "configData", TestUtils.configData(station));
        HttpHeaders headers = new HttpHeaders();
        headers.add("location", "locationheader");
        it.gov.pagopa.gen.wispconverter.client.checkout.model.CartResponseDto cartResponseDto = new it.gov.pagopa.gen.wispconverter.client.checkout.model.CartResponseDto();
        cartResponseDto.setCheckoutRedirectUrl(URI.create("http://www.google.com"));
        TestUtils.setMock(checkoutClient, ResponseEntity.status(HttpStatus.FOUND).headers(headers).body(cartResponseDto));
        it.gov.pagopa.gen.wispconverter.client.iuvgenerator.model.IUVGenerationResponseDto iuvGenerationModelResponseDto = new IUVGenerationResponseDto();
        iuvGenerationModelResponseDto.setIuv("00000000");
        TestUtils.setMock(iuveneratorClient, ResponseEntity.ok().body(iuvGenerationModelResponseDto));
        TestUtils.setMockGetExceptionNotFound(gpdClient);
        TestUtils.setMockPost(gpdClient, ResponseEntity.ok().body(getPaymentPositionModelDto()));
        TestUtils.setMock(decouplerCachingClient, ResponseEntity.ok().build());
        when(rptRequestRepository.findById(any())).thenReturn(
                Optional.of(
                        RPTRequestEntity.builder().primitive("nodoInviaCarrelloRPT")
                                .payload(
                                        TestUtils.zipAndEncode(
                                                TestUtils.getCarrelloPayload(1, station,
                                                        "100.00", true, "{idCarrello}"
                                                ))
                                ).build()
                )
        );
        when(redisSimpleTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));


        mvc.perform(MockMvcRequestBuilders.get(REDIRECT_PATH + "?idSession=aaaaaaaaaaaa").accept(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful())
                .andDo(
                        result -> {
                            assertNotNull(result);
                            assertNotNull(result.getResponse());
                            assertTrue(result.getResponse().getContentAsString().contains("Riprova, oppure contatta l'assistenza"));
                        });

        verify(checkoutClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(iuveneratorClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gpdClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(decouplerCachingClient, times(0)).invokeAPI(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

}
