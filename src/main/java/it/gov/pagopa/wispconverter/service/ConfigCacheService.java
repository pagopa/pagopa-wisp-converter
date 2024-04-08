package it.gov.pagopa.wispconverter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigDataV1Dto;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@Getter
@CacheConfig(cacheNames="cache")
@Slf4j
@RequiredArgsConstructor
public class ConfigCacheService {

    private final it.gov.pagopa.gen.wispconverter.client.cache.invoker.ApiClient configCacheClient;

    private it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigDataV1Dto configData;

    @Value("${client.cache.keys}")
    private List<String> cacheKeys;

    public void getCache() {
        loadCache();
    }

    @Cacheable
    public void loadCache() {
        log.info("loadCache from cache api");
        try {
            it.gov.pagopa.gen.wispconverter.client.cache.api.CacheApi apiInstance = new it.gov.pagopa.gen.wispconverter.client.cache.api.CacheApi(configCacheClient);
            configData = apiInstance.get(cacheKeys);
        } catch (Exception e) {
            log.error("Cannot get cache", e);
        }
    }

}
