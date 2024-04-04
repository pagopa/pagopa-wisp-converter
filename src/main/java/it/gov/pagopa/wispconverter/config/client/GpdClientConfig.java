package it.gov.pagopa.wispconverter.config.client;

import it.gov.pagopa.wispconverter.service.ReService;
import it.gov.pagopa.wispconverter.util.client.decouplercaching.DecouplerCachingClientLogging;
import it.gov.pagopa.wispconverter.util.client.gpd.GpdClient;
import it.gov.pagopa.wispconverter.util.client.gpd.GpdClientLogging;
import it.gov.pagopa.wispconverter.util.client.gpd.GpdClientResponseErrorHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class GpdClientConfig {

    @Value("${client.gpd.read-timeout}")
    private Integer readTimeout;

    @Value("${client.gpd.connect-timeout}")
    private Integer connectTimeout;

    @Value("${client.gpd.base-path}")
    private String basePath;

    @Value("${client.gpd.api-key}")
    private String apiKey;

    @Value("${log.client.gpd.request.include-headers}")
    private boolean clientRequestIncludeHeaders;
    @Value("${log.client.gpd.request.include-payload}")
    private boolean clientRequestIncludePayload;
    @Value("${log.client.gpd.request.max-payload-length}")
    private int clientRequestMaxLength;
    @Value("${log.client.gpd.response.include-headers}")
    private boolean clientResponseIncludeHeaders;
    @Value("${log.client.gpd.response.include-payload}")
    private boolean clientResponseIncludePayload;
    @Value("${log.client.gpd.response.max-payload-length}")
    private int clientResponseMaxLength;

    @Value("${log.client.gpd.mask.header.name}")
    private String maskHeaderName;

    @Value("${log.client.gpd.request.pretty}")
    private boolean clientRequestPretty;

    @Value("${log.client.gpd.response.pretty}")
    private boolean clientResponsePretty;

    @Bean
    public GpdClient gpdClient(ReService reService) {
        GpdClientLogging clientLogging = new GpdClientLogging();
        clientLogging.setRequestIncludeHeaders(clientRequestIncludeHeaders);
        clientLogging.setRequestIncludePayload(clientRequestIncludePayload);
        clientLogging.setRequestMaxPayloadLength(clientRequestMaxLength);
        clientLogging.setRequestHeaderPredicate(p -> !p.equals(maskHeaderName));
        clientLogging.setRequestPretty(clientRequestPretty);

        clientLogging.setResponseIncludeHeaders(clientResponseIncludeHeaders);
        clientLogging.setResponseIncludePayload(clientResponseIncludePayload);
        clientLogging.setResponseMaxPayloadLength(clientResponseMaxLength);
        clientLogging.setResponsePretty(clientResponsePretty);

        GpdClient client = new GpdClient(readTimeout, connectTimeout);
        client.addCustomLoggingInterceptor(clientLogging);
        client.addCustomErrorHandler(new GpdClientResponseErrorHandler());

        client.setBasePath(basePath);
        client.setBasePath(apiKey);

        return client;
    }
}
