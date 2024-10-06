package it.gov.pagopa.wispconverter.service;

import it.gov.pagopa.gen.wispconverter.client.cache.model.*;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Service
@CacheConfig(cacheNames = "cache")
@Slf4j
@RequiredArgsConstructor
public class ConfigCacheService {

    private final it.gov.pagopa.gen.wispconverter.client.cache.invoker.ApiClient configCacheClient;

    @Getter
    private it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigDataV1Dto configData;

    @Value("${client.cache.keys}")
    private List<String> cacheKeys;

    public void refreshCache() {
        log.debug("loadCache from cache api");

        try {
            it.gov.pagopa.gen.wispconverter.client.cache.api.CacheApi apiInstance = new it.gov.pagopa.gen.wispconverter.client.cache.api.CacheApi(configCacheClient);
            if (configData == null) {
                configData = apiInstance.get(cacheKeys);
            } else {
                CacheVersionDto id = apiInstance.id();
                if (!configData.getVersion().equals(id.getVersion())) {
                    configData = apiInstance.get(cacheKeys);
                }
            }

        } catch (RestClientException e) {
            throw new AppException(AppErrorCodeMessageEnum.CLIENT_DECOUPLER_CACHING,
                    String.format("RestClientException ERROR [%s] - %s", e.getCause().getClass().getCanonicalName(), e.getMessage()));
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
