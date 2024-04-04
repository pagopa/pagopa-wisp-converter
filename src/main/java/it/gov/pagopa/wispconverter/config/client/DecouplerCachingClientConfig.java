package it.gov.pagopa.wispconverter.config.client;

import it.gov.pagopa.wispconverter.service.ReService;
import it.gov.pagopa.wispconverter.util.client.checkout.CheckoutClient;
import it.gov.pagopa.wispconverter.util.client.checkout.CheckoutClientLogging;
import it.gov.pagopa.wispconverter.util.client.checkout.CheckoutClientResponseErrorHandler;
import it.gov.pagopa.wispconverter.util.client.decouplercaching.DecouplerCachingClient;
import it.gov.pagopa.wispconverter.util.client.decouplercaching.DecouplerCachingClientLogging;
import it.gov.pagopa.wispconverter.util.client.decouplercaching.DecouplerCachingClientResponseErrorHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class DecouplerCachingClientConfig {

    @Value("${client.decoupler-caching.read-timeout}")
    private Integer readTimeout;

    @Value("${client.decoupler-caching.connect-timeout}")
    private Integer connectTimeout;

    @Value("${client.decoupler-caching.base-path}")
    private String basePath;

    @Value("${client.decoupler-caching.api-key}")
    private String apiKey;

    @Value("${log.client.decoupler-caching.request.include-headers}")
    private boolean clientRequestIncludeHeaders;
    @Value("${log.client.decoupler-caching.request.include-payload}")
    private boolean clientRequestIncludePayload;
    @Value("${log.client.decoupler-caching.request.max-payload-length}")
    private int clientRequestMaxLength;
    @Value("${log.client.decoupler-caching.response.include-headers}")
    private boolean clientResponseIncludeHeaders;
    @Value("${log.client.decoupler-caching.response.include-payload}")
    private boolean clientResponseIncludePayload;
    @Value("${log.client.decoupler-caching.response.max-payload-length}")
    private int clientResponseMaxLength;

    @Value("${log.client.decoupler-caching.mask.header.name}")
    private String maskHeaderName;

    @Value("${log.client.decoupler-caching.request.pretty}")
    private boolean clientRequestPretty;

    @Value("${log.client.decoupler-caching.response.pretty}")
    private boolean clientResponsePretty;

    @Bean
    public DecouplerCachingClient decouplerCachingClient(ReService reService) {
        DecouplerCachingClientLogging clientLogging = new DecouplerCachingClientLogging();
        clientLogging.setRequestIncludeHeaders(clientRequestIncludeHeaders);
        clientLogging.setRequestIncludePayload(clientRequestIncludePayload);
        clientLogging.setRequestMaxPayloadLength(clientRequestMaxLength);
        clientLogging.setRequestHeaderPredicate(p -> !p.equals(maskHeaderName));
        clientLogging.setRequestPretty(clientRequestPretty);

        clientLogging.setResponseIncludeHeaders(clientResponseIncludeHeaders);
        clientLogging.setResponseIncludePayload(clientResponseIncludePayload);
        clientLogging.setResponseMaxPayloadLength(clientResponseMaxLength);
        clientLogging.setResponsePretty(clientResponsePretty);

        DecouplerCachingClient client = new DecouplerCachingClient(readTimeout, connectTimeout);
        client.addCustomLoggingInterceptor(clientLogging);
        client.addCustomErrorHandler(new DecouplerCachingClientResponseErrorHandler());

        client.setBasePath(basePath);
        client.setBasePath(apiKey);

        return client;
    }
}
