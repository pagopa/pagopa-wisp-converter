package it.gov.pagopa.wispconverter.config.client;

import it.gov.pagopa.wispconverter.service.ReService;
import it.gov.pagopa.wispconverter.util.client.gpd.GpdClientLogging;
import it.gov.pagopa.wispconverter.util.client.iuvgenerator.IuvGeneratorClient;
import it.gov.pagopa.wispconverter.util.client.iuvgenerator.IuvGeneratorClientLogging;
import it.gov.pagopa.wispconverter.util.client.iuvgenerator.IuvGeneratorClientResponseErrorHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class IuvGeneratorClientConfig {

    @Value("${client.iuvgenerator.read-timeout}")
    private Integer readTimeout;

    @Value("${client.iuvgenerator.connect-timeout}")
    private Integer connectTimeout;

    @Value("${client.iuvgenerator.base-path}")
    private String basePath;

    @Value("${client.iuvgenerator.api-key}")
    private String apiKey;

    @Value("${log.client.iuvgenerator.request.include-headers}")
    private boolean clientRequestIncludeHeaders;
    @Value("${log.client.iuvgenerator.request.include-payload}")
    private boolean clientRequestIncludePayload;
    @Value("${log.client.iuvgenerator.request.max-payload-length}")
    private int clientRequestMaxLength;
    @Value("${log.client.iuvgenerator.response.include-headers}")
    private boolean clientResponseIncludeHeaders;
    @Value("${log.client.iuvgenerator.response.include-payload}")
    private boolean clientResponseIncludePayload;
    @Value("${log.client.iuvgenerator.response.max-payload-length}")
    private int clientResponseMaxLength;

    @Value("${log.client.iuvgenerator.mask.header.name}")
    private String maskHeaderName;

    @Value("${log.client.iuvgenerator.request.pretty}")
    private boolean clientRequestPretty;

    @Value("${log.client.iuvgenerator.response.pretty}")
    private boolean clientResponsePretty;


    @Bean
    public IuvGeneratorClient iuvGeneratorClient(ReService reService) {
        IuvGeneratorClientLogging clientLogging = new IuvGeneratorClientLogging();
        clientLogging.setRequestIncludeHeaders(clientRequestIncludeHeaders);
        clientLogging.setRequestIncludePayload(clientRequestIncludePayload);
        clientLogging.setRequestMaxPayloadLength(clientRequestMaxLength);
        clientLogging.setRequestHeaderPredicate(p -> !p.equals(maskHeaderName));
        clientLogging.setRequestPretty(clientRequestPretty);

        clientLogging.setResponseIncludeHeaders(clientResponseIncludeHeaders);
        clientLogging.setResponseIncludePayload(clientResponseIncludePayload);
        clientLogging.setResponseMaxPayloadLength(clientResponseMaxLength);
        clientLogging.setResponsePretty(clientResponsePretty);

        IuvGeneratorClient client = new IuvGeneratorClient(readTimeout, connectTimeout);
        client.addCustomLoggingInterceptor(clientLogging);
        client.addCustomErrorHandler(new IuvGeneratorClientResponseErrorHandler());

        client.setBasePath(basePath);
        client.setBasePath(apiKey);

        return client;
    }
}
