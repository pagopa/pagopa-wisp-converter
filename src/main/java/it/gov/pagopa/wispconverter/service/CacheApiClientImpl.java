package it.gov.pagopa.wispconverter.service;

import it.gov.pagopa.gen.wispconverter.client.cache.invoker.ApiClient;
import it.gov.pagopa.gen.wispconverter.client.cache.model.CacheVersionDto;
import it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigDataV1Dto;
import org.springframework.stereotype.Component;

@Component
public class CacheApiClientImpl implements CacheApiClient {

    private final ApiClient apiClient;

    public CacheApiClientImpl(ApiClient apiClient) {
        this.apiClient = apiClient;
    }

    private it.gov.pagopa.gen.wispconverter.client.cache.api.CacheApi api() {
        return new it.gov.pagopa.gen.wispconverter.client.cache.api.CacheApi(apiClient);
    }

    @Override
    public ConfigDataV1Dto cache(boolean param) {
        return api().cache(param);
    }

    @Override
    public CacheVersionDto idV1() {
        return api().idV1();
    }
}
