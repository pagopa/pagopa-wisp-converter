package it.gov.pagopa.wispconverter.util.client.decouplercaching;

import it.gov.pagopa.wispconverter.repository.model.enumz.WorkflowStatus;
import it.gov.pagopa.wispconverter.service.ReService;
import it.gov.pagopa.wispconverter.util.client.AbstractAppClientLoggingInterceptor;
import it.gov.pagopa.wispconverter.util.client.ClientServiceEnum;
import it.gov.pagopa.wispconverter.util.client.RequestResponseLoggingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;

import java.util.regex.Pattern;

@Slf4j
public class DecouplerCachingClientLoggingInterceptor extends AbstractAppClientLoggingInterceptor {

    private final Pattern sessionIdPattern = Pattern.compile("\\\"sessionId\\\":\\\"([a-zA-Z0-9-]+)\\\"");

    public DecouplerCachingClientLoggingInterceptor(RequestResponseLoggingProperties clientLoggingProperties, ReService reService, Boolean isTracingOfClientOnREEnabled) {
        super(clientLoggingProperties, reService, ClientServiceEnum.DECOUPLER);

        // avoiding persistence of client invocation on RE
        if (Boolean.FALSE.equals(isTracingOfClientOnREEnabled)) {
            avoidEventPersistenceOnRE();
        }
    }

    @Override
    protected WorkflowStatus getOperationStatus(String url, HttpMethod httpMethod) {
        WorkflowStatus status = null;
        if (url.contains("/save-mapping")) {
            status = WorkflowStatus.COMMUNICATION_WITH_APIM_FOR_CACHING_RPT_MAPPING_PROCESSED;
        } else if (url.contains("/save-cart-mapping")) {
            status = WorkflowStatus.COMMUNICATION_WITH_APIM_FOR_CACHING_SESSION_MAPPING_PROCESSED;
        } else if (url.contains("/delete-sessionId")) {
            status = WorkflowStatus.COMMUNICATION_WITH_APIM_FOR_DELETING_SESSION_MAPPING_PROCESSED;

        }
        return status;
    }

    @Override
    protected void setInMDCAfterClientResponse(String payload) {
        // nothing to do
    }

    @Override
    protected void deleteFromMDCAfterClientResponse() {
        // nothing to do
    }
}
