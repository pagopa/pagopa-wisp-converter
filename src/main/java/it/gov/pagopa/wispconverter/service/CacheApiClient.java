package it.gov.pagopa.wispconverter.service;

import it.gov.pagopa.gen.wispconverter.client.cache.model.CacheVersionDto;
import it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigDataV1Dto;

public interface CacheApiClient {
    ConfigDataV1Dto cache(boolean param);
    CacheVersionDto idV1();
}
