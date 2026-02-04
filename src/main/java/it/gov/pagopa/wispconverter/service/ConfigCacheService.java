package it.gov.pagopa.wispconverter.service;

import it.gov.pagopa.gen.wispconverter.client.cache.invoker.ApiClient;
import it.gov.pagopa.gen.wispconverter.client.cache.model.CacheVersionDto;
import it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigDataV1Dto;
import it.gov.pagopa.gen.wispconverter.client.cache.model.CreditorInstitutionDto;
import it.gov.pagopa.gen.wispconverter.client.cache.model.StationCreditorInstitutionDto;
import it.gov.pagopa.gen.wispconverter.client.cache.model.StationDto;
import it.gov.pagopa.wispconverter.config.client.AppInsightTelemetryClient;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@CacheConfig(cacheNames = "cache")
@Slf4j
public class ConfigCacheService {

    private final ApiClient configCacheClient;
    private final AppInsightTelemetryClient telemetryClient;

    @Getter
    private ConfigDataV1Dto configData;

    private final ScheduledExecutorService delayExecutor;
    private final int maxRetry;
    private final long baseBackoffMs;

    @Autowired
    public ConfigCacheService(ApiClient configCacheClient,
                              AppInsightTelemetryClient telemetryClient,
                              @Qualifier("delayExecutor") ScheduledExecutorService delayExecutor,
                              @Value("${config.cache.maxRetry:3}") int maxRetry,
                              @Value("${config.cache.baseBackoffMs:500}") long baseBackoffMs) {
        this.configCacheClient = configCacheClient;
        this.telemetryClient = telemetryClient;
        this.delayExecutor = delayExecutor;
        this.maxRetry = maxRetry;
        this.baseBackoffMs = baseBackoffMs;
    }

    public void refreshCache() {
        log.info("LoadCache from cache API");

        // use non-blocking async retries: perform initial synchronous attempt and, on failure,
        // schedule further attempts only if a cached config is already present.
        attemptRefresh(1);
    }

    // attempt number is 1-based. The method performs the attempt synchronously; if it fails and
    // configData != null it schedules the next attempt asynchronously (non-blocking) until
    // attempt == maxRetry.
    private void attemptRefresh(int attempt) {
        try {
            it.gov.pagopa.gen.wispconverter.client.cache.api.CacheApi apiInstance = new it.gov.pagopa.gen.wispconverter.client.cache.api.CacheApi(configCacheClient);
            if (configData == null) {
                configData = apiInstance.cache(false);
                log.info("LoadCache from cache API...done. Version: {}", configData.getVersion());
            } else {
                CacheVersionDto id = apiInstance.idV1();
                if (!configData.getVersion().equals(id.getVersion())) {
                    configData = apiInstance.cache(false);
                    log.info("LoadCache v1 from cache API...done. Version: {}", configData.getVersion());
                } else {
                    log.info("LoadCache check succeeded, cache version unchanged: {}", configData.getVersion());
                }
            }
        } catch (AppException | RestClientException e) {
            // if there is no cache yet, fail fast and throw immediately
            if (configData == null) {
                log.error("[WISP] LoadCache from cache API failed and no cached version available. Throwing error. Exception: {} - {}",
                        e.getClass().getSimpleName(), e.getMessage(), e);
                String causeClass = (e.getCause() != null) ? e.getCause().getClass().getCanonicalName() : "unknown";
                throw new AppException(AppErrorCodeMessageEnum.CLIENT_APICONFIGCACHE,
                        String.format("RestClientException ERROR [%s] - %s", causeClass, e.getMessage()));
            }

            // if we already have a cache, schedule next attempt asynchronously (if attempts remains)
            if (attempt >= maxRetry) {
                String currentVersion = configData != null ? configData.getVersion() : "null";
                String message = String.format("[WISP] LoadCache from cache API failed after %s attempts. Keeping existing cached version: %s. Last error: %s - %s",
                attempt, currentVersion, e.getClass().getSimpleName(), e.getMessage());
                log.error(message, e);
                telemetryClient.createCustomEventForAlert(
                        AppErrorCodeMessageEnum.CONFIGURATION_OBSOLETE_CACHE,
                        message,
                        e
                );
                return; // keep using existing cache
            }

            final int nextAttempt = attempt + 1;
            final long delay = baseBackoffMs * (1L << (attempt - 1));
            final String prevVersion = configData != null ? configData.getVersion() : "null";

            log.warn("[WISP] LoadCache attempt {} failed, scheduling async retry {} after {} ms. Exception: {} - {}. Current cache version: {}",
                    attempt, nextAttempt, delay, e.getClass().getSimpleName(), e.getMessage(), prevVersion);

            // schedule async retry without blocking the caller
            delayExecutor.schedule(() -> {
                try {
                    attemptRefresh(nextAttempt);
                } catch (Exception ex) {
                    // scheduled task: log fully. If configData is null we cannot propagate to caller.
                    log.error("[WISP] Async refresh attempt {} failed and cannot be propagated: {} - {}",
                            nextAttempt, ex.getClass().getSimpleName(), ex.getMessage(), ex);
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    public String getCreditorInstitutionNameFromCache(String creditorInstitutionId) {

        // get cached data
        ConfigDataV1Dto cache = this.getConfigData();
        if (cache == null) {
            throw new AppException(AppErrorCodeMessageEnum.CONFIGURATION_INVALID_CACHE);
        }

        // retrieving station by station identifier
        Map<String, CreditorInstitutionDto> creditorInstitutions = cache.getCreditorInstitutions();
        CreditorInstitutionDto creditorInstitution = creditorInstitutions.get(creditorInstitutionId);
        return creditorInstitution != null ? creditorInstitution.getBusinessName() : "-";
    }

    public StationDto getStationByIdFromCache(String stationId) {

        // get cached data
        ConfigDataV1Dto cache = this.getConfigData();
        if (cache == null) {
            throw new AppException(AppErrorCodeMessageEnum.CONFIGURATION_INVALID_CACHE);
        }

        // retrieving station by station identifier
        Map<String, StationDto> stations = cache.getStations();
        StationDto station = stations.get(stationId);
        if (station == null) {
            throw new AppException(AppErrorCodeMessageEnum.CONFIGURATION_INVALID_STATION, stationId);
        }

        return station;
    }

    public StationDto getStationsByCreditorInstitutionAndSegregationCodeFromCache(String creditorInstitutionId, Long segregationCode) {

        // get cached data
        ConfigDataV1Dto cache = this.getConfigData();
        if (cache == null) {
            throw new AppException(AppErrorCodeMessageEnum.CONFIGURATION_INVALID_CACHE);
        }

        // retrieving relations between creditor institution and station in order to filter by segregation code
        Map<String, StationCreditorInstitutionDto> creditorInstitutionStations = cache.getCreditorInstitutionStations();
        StationCreditorInstitutionDto stationCreditorInstitution = creditorInstitutionStations.values().stream()
                .filter(ciStation -> ciStation.getCreditorInstitutionCode().equals(creditorInstitutionId) && segregationCode.equals(ciStation.getSegregationCode()))
                .findFirst()
                .orElseThrow(() -> new AppException(AppErrorCodeMessageEnum.CONFIGURATION_INVALID_CREDITOR_INSTITUTION_STATION, segregationCode, creditorInstitutionId));

        // retrieving station by station identifier
        Map<String, StationDto> stations = cache.getStations();
        StationDto station = stations.get(stationCreditorInstitution.getStationCode());
        if (station == null) {
            throw new AppException(AppErrorCodeMessageEnum.CONFIGURATION_INVALID_STATION, stationCreditorInstitution.getStationCode());
        }

        return station;
    }

}
