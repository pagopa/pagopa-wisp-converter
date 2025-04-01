package it.gov.pagopa.wispconverter.util.client.iuvgenerator;

import it.gov.pagopa.wispconverter.repository.model.enumz.WorkflowStatus;
import it.gov.pagopa.wispconverter.service.ReService;
import it.gov.pagopa.wispconverter.util.Constants;
import it.gov.pagopa.wispconverter.util.client.AbstractAppClientLoggingInterceptor;
import it.gov.pagopa.wispconverter.util.client.ClientServiceEnum;
import it.gov.pagopa.wispconverter.util.client.RequestResponseLoggingProperties;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class IuvGeneratorClientLoggingInterceptor extends AbstractAppClientLoggingInterceptor {

    private final Pattern iuvPattern = Pattern.compile("\\\"iuv\\\":\\\"([0-9]+)\\\"");

    public IuvGeneratorClientLoggingInterceptor(RequestResponseLoggingProperties clientLoggingProperties, ReService reService, Boolean isTracingOfClientOnREEnabled) {
        super(clientLoggingProperties, reService, ClientServiceEnum.IUV_GENERATOR);

        // avoiding persistence of client invocation on RE
        if (Boolean.FALSE.equals(isTracingOfClientOnREEnabled)) {
            avoidEventPersistenceOnRE();
        }
    }

    @Override
    protected WorkflowStatus getOperationStatus(String url, HttpMethod httpMethod) {
        return WorkflowStatus.COMMUNICATION_WITH_IUVGENERATOR_FOR_NAV_CREATION_PROCESSED;
    }

    @Override
    protected void setInMDCAfterClientResponse(String payload) {
        try {
            if (payload != null && !payload.isBlank()) {
                Matcher matcher = iuvPattern.matcher(payload);
                if (matcher.find()) {
                    String noticeNumber = matcher.group(1);
                    if (noticeNumber != null && !noticeNumber.isBlank()) {
                        MDC.put(Constants.MDC_NOTICE_NUMBER, "3" + noticeNumber);
                    }
                }
            }
        } catch (Exception e) {
            // catch this but do nothing, as nothing is required to do
        }
    }

    @Override
    protected void deleteFromMDCAfterClientResponse() {
        MDC.remove(Constants.MDC_NOTICE_NUMBER);
    }


}
