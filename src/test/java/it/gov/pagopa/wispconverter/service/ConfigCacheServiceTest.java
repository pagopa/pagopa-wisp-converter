package it.gov.pagopa.wispconverter.service;

import it.gov.pagopa.gen.wispconverter.client.cache.invoker.ApiClient;
import it.gov.pagopa.gen.wispconverter.client.cache.model.CacheVersionDto;
import it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigDataV1Dto;
import it.gov.pagopa.wispconverter.config.client.AppInsightTelemetryClient;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ConfigCacheServiceTest {

    private ApiClient apiClient;
    private AppInsightTelemetryClient telemetryClient;
    private ScheduledExecutorService executor;
    private CacheApiClient cacheApiClient;

    @BeforeEach
    public void setup() {
        apiClient = mock(ApiClient.class);
        telemetryClient = mock(AppInsightTelemetryClient.class);
        // use a mock ScheduledExecutorService to make tests deterministic
        executor = mock(ScheduledExecutorService.class);
        cacheApiClient = mock(CacheApiClient.class);
    }

    @Test
    public void refreshCache_successInitialLoad_shouldPopulateConfigData() throws Exception {
        // prepare Api returning a ConfigData
        ConfigDataV1Dto config = new ConfigDataV1Dto();
        config.setVersion("v1");
        config.setCreditorInstitutions(new HashMap<>());
        config.setStations(new HashMap<>());
        config.setCreditorInstitutionStations(new HashMap<>());

        when(cacheApiClient.cache(false)).thenReturn(config);

        ConfigCacheService sut = new ConfigCacheService(apiClient, telemetryClient, executor, 3, 100L, cacheApiClient);

        sut.refreshCache();

        assertNotNull(sut.getConfigData());
        assertEquals("v1", sut.getConfigData().getVersion());

        verify(cacheApiClient, times(1)).cache(false);
    }

    @Test
    public void refreshCache_failureNoCache_shouldThrowAppException() throws Exception {
        when(cacheApiClient.cache(false)).thenThrow(new RestClientException("down"));

        ConfigCacheService sut = new ConfigCacheService(apiClient, telemetryClient, executor, 3, 100L, cacheApiClient);

        AppException ex = assertThrows(AppException.class, () -> sut.refreshCache());
        assertEquals(it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum.CLIENT_APICONFIGCACHE, ex.getError());
    }

    @Test
    public void refreshCache_failureWithCache_shouldScheduleRetry() throws Exception {
        // prepare initial config in service
        ConfigDataV1Dto initialConfig = new ConfigDataV1Dto();
        initialConfig.setVersion("v1");
        initialConfig.setCreditorInstitutions(new HashMap<>());
        initialConfig.setStations(new HashMap<>());
        initialConfig.setCreditorInstitutionStations(new HashMap<>());

        // simulate idV1 throwing first then returning same version on retry
        when(cacheApiClient.idV1()).thenThrow(new RestClientException("down")).thenReturn(new CacheVersionDto() {{ setVersion("v1"); }});

        ConfigCacheService sut = new ConfigCacheService(apiClient, telemetryClient, executor, 3, 10L, cacheApiClient);
        // inject existing cache
        java.lang.reflect.Field f = ConfigCacheService.class.getDeclaredField("configData");
        f.setAccessible(true);
        f.set(sut, initialConfig);

        // capture the scheduled runnable and execute it to simulate async retry
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(executor.schedule(runnableCaptor.capture(), anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            // execute immediately to simulate the scheduled retry
            r.run();
            return mock(ScheduledFuture.class);
        });

        sut.refreshCache();

        // verify that schedule was called (retry scheduled)
        verify(executor, atLeastOnce()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

        // verify idV1 invoked at least once
        verify(cacheApiClient, atLeastOnce()).idV1();

        // telemetry should not be called (not yet at max retries)
        verify(telemetryClient, never()).createCustomEventForAlert(any(), anyString(), any());
    }
}
