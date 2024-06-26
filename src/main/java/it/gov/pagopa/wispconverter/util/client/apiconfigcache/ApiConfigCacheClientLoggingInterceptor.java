package it.gov.pagopa.wispconverter.util.client.apiconfigcache;

import it.gov.pagopa.wispconverter.service.ReService;
import it.gov.pagopa.wispconverter.util.client.AbstractAppClientLoggingInterceptor;
import it.gov.pagopa.wispconverter.util.client.ClientServiceEnum;
import it.gov.pagopa.wispconverter.util.client.RequestResponseLoggingProperties;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;

@Slf4j
public class ApiConfigCacheClientLoggingInterceptor extends AbstractAppClientLoggingInterceptor {

    public ApiConfigCacheClientLoggingInterceptor(RequestResponseLoggingProperties clientLoggingProperties, ReService reService) {
        super(clientLoggingProperties, reService, ClientServiceEnum.API_CONFIG_CACHE);

        // avoiding persistence of APIConfig Cache invocation on RE
        avoidEventPersistenceOnRE();
    }

    @Override
    protected void request(String clientOperationId, String operationId, HttpRequest request, byte[] reqBody) {
        if (log.isDebugEnabled()) {
            log.debug(createRequestMessage(clientOperationId, operationId, request, reqBody));
        }
    }

    @SneakyThrows
    @Override
    protected void response(String clientOperationId, String operationId, String clientExecutionTime, HttpRequest request, ClientHttpResponse response) {
        if (log.isDebugEnabled()) {
            log.debug(createResponseMessage(clientOperationId, operationId, clientExecutionTime, request, response));
        }
    }
}
