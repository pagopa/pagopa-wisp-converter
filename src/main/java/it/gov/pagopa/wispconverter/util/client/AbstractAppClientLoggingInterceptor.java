package it.gov.pagopa.wispconverter.util.client;

import it.gov.pagopa.wispconverter.repository.model.enumz.OutcomeEnum;
import it.gov.pagopa.wispconverter.repository.model.enumz.WorkflowStatus;
import it.gov.pagopa.wispconverter.service.ReService;
import it.gov.pagopa.wispconverter.service.model.re.ReRequestContext;
import it.gov.pagopa.wispconverter.service.model.re.ReResponseContext;
import it.gov.pagopa.wispconverter.util.CommonUtility;
import it.gov.pagopa.wispconverter.util.Constants;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public abstract class AbstractAppClientLoggingInterceptor implements ClientHttpRequestInterceptor {

    public static final String REQUEST_DEFAULT_MESSAGE_PREFIX =
            "===> CLIENT Request OPERATION_ID=%s, CLIENT_OPERATION_ID=%s - ";
    public static final String RESPONSE_DEFAULT_MESSAGE_PREFIX =
            "<=== CLIENT Response OPERATION_ID=%s, CLIENT_OPERATION_ID=%s -";
    public static final String DELIMITER = "\", \"";
    private static final int REQUEST_DEFAULT_MAX_PAYLOAD_LENGTH = 50;
    private static final int RESPONSE_DEFAULT_MAX_PAYLOAD_LENGTH = 50;
    private static final String SPACE = " ";
    private static final String PRETTY_OUT = "\n===> *";
    private static final String PRETTY_IN = "\n<=== *";
    private final ReService reService;
    protected ClientServiceEnum clientServiceEnum;
    private boolean requestIncludeHeaders;
    private boolean responseIncludeHeaders;
    private boolean requestIncludePayload;
    private boolean responseIncludePayload;
    private Predicate<String> requestHeaderPredicate;
    private Predicate<String> responseHeaderPredicate;
    private int requestMaxPayloadLength = REQUEST_DEFAULT_MAX_PAYLOAD_LENGTH;
    private int responseMaxPayloadLength = RESPONSE_DEFAULT_MAX_PAYLOAD_LENGTH;
    private boolean requestPretty;
    private boolean responsePretty;
    private boolean mustPersistEventOnRE;

    protected AbstractAppClientLoggingInterceptor(
            RequestResponseLoggingProperties clientLoggingProperties,
            ReService reService,
            ClientServiceEnum clientServiceEnum) {
        this.reService = reService;
        this.clientServiceEnum = clientServiceEnum;
        this.mustPersistEventOnRE = true;

        if (clientLoggingProperties != null) {
            RequestResponseLoggingProperties.Request request = clientLoggingProperties.getRequest();
            if (request != null) {
                this.requestIncludeHeaders = request.isIncludeHeaders();
                this.requestIncludePayload = request.isIncludePayload();
                this.requestMaxPayloadLength =
                        request.getMaxPayloadLength() != null
                                ? request.getMaxPayloadLength()
                                : REQUEST_DEFAULT_MAX_PAYLOAD_LENGTH;
                this.requestHeaderPredicate = s -> !s.equals(request.getMaskHeaderName());
                this.requestPretty = request.isPretty();
            }
            RequestResponseLoggingProperties.Response response = clientLoggingProperties.getResponse();
            if (response != null) {
                this.responseIncludeHeaders = response.isIncludeHeaders();
                this.responseIncludePayload = response.isIncludePayload();
                this.responseMaxPayloadLength =
                        response.getMaxPayloadLength() != null
                                ? response.getMaxPayloadLength()
                                : RESPONSE_DEFAULT_MAX_PAYLOAD_LENGTH;
                this.responseHeaderPredicate = null;
                this.responsePretty = response.isPretty();
            }
        }
    }

    private static void appendURL(HttpRequest request, StringBuilder msg) {
        msg.append("path: ").append(request.getMethod()).append(' ');
        msg.append(request.getURI());
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {

        String startClient = String.valueOf(System.currentTimeMillis());
        String clientOperationId = UUID.randomUUID().toString();
        String operationId = MDC.get(Constants.MDC_OPERATION_ID);
        logRequest(clientOperationId, operationId, request, body);
        OutcomeEnum outcome;
        WorkflowStatus status = getOperationStatus(request.getURI().toString(), request.getMethod());

        ClientHttpResponse response = null;
        try {

            response = execution.execute(request, body);

            String executionClientTime = CommonUtility.getExecutionTime(startClient);
            MDC.put(Constants.MDC_CLIENT_EXECUTION_TIME, executionClientTime);

            if (response.getStatusCode().is2xxSuccessful()) {
                outcome = OutcomeEnum.OK;
            } else {
                outcome = OutcomeEnum.COMMUNICATION_FAILURE;
            }

            logResponse(clientOperationId, operationId, executionClientTime, request, response);
            generateRe(request, body, response, status, outcome);

        } catch (Exception e) {

            outcome = OutcomeEnum.COMMUNICATION_FAILURE;
            String executionClientTime = CommonUtility.getExecutionTime(startClient);
            MDC.put(Constants.MDC_CLIENT_EXECUTION_TIME, executionClientTime);
            logResponse(clientOperationId, operationId, executionClientTime, request, null);
            generateRe(request, body, response, status, outcome);
            throw e;

        } finally {
            MDC.remove(Constants.MDC_CLIENT_EXECUTION_TIME);
        }

        return response;
    }

    protected String createRequestMessage(
            String clientOperationId, String operationId, HttpRequest request, @Nullable byte[] reqBody) {
        StringBuilder msg = new StringBuilder();
        appendContext(clientOperationId, operationId, msg);
        appendURL(request, msg);
        appendHeaders(request, msg);
        appendBody(reqBody, msg);
        return msg.toString();
    }

    private void appendContext(String clientOperationId, String operationId, StringBuilder msg) {
        msg.append(String.format(REQUEST_DEFAULT_MESSAGE_PREFIX, operationId, clientOperationId));
        if (this.requestPretty) {
            msg.append(PRETTY_OUT).append(SPACE);
        }
    }

    private void appendContext2(String clientOperationId, String operationId, StringBuilder msg) {
        msg.append(String.format(RESPONSE_DEFAULT_MESSAGE_PREFIX, operationId, clientOperationId));
        if (this.responsePretty) {
            msg.append(PRETTY_IN).append(SPACE);
        }
    }

    private void appendBody(@Nullable byte[] reqBody, StringBuilder msg) {
        if (this.requestIncludePayload && reqBody != null) {
            String payload = new String(reqBody, StandardCharsets.UTF_8);
            if (this.requestPretty) {
                msg.append(PRETTY_OUT).append(SPACE);
            } else {
                msg.append(", ");
            }
            msg.append("payload: ").append(payload);
        }
    }

    private void appendHeaders(HttpRequest request, StringBuilder msg) {
        if (this.requestIncludeHeaders) {
            HttpHeaders headers = new HttpHeaders();
            request.getHeaders().forEach((s, h) -> headers.add(s, StringUtils.join(h, ",")));
            maskSensitiveHeader(this.requestHeaderPredicate, headers);
            String formatRequestHeaders = formatRequestHeaders(headers);
            if (formatRequestHeaders != null) {
                if (this.requestPretty) {
                    msg.append(PRETTY_OUT).append(SPACE);
                } else {
                    msg.append(", ");
                }
                msg.append("headers: [").append(formatRequestHeaders);
                if (this.requestPretty) {
                    msg.append(PRETTY_OUT).append(SPACE);
                }
                msg.append("]");
            }
        }
    }

    private void maskSensitiveHeader(Predicate<String> requestHeaderPredicate, HttpHeaders headers) {
        if (requestHeaderPredicate != null) {
            headers.forEach(
                    (key, value) -> {
                        if (!this.requestHeaderPredicate.test(key)) {
                            headers.set(key, "masked");
                        }
                    });
        }
    }

    protected String createResponseMessage(
            String clientOperationId,
            String operationId,
            String clientExecutionTime,
            HttpRequest request,
            ClientHttpResponse response)
            throws IOException {

        StringBuilder msg = new StringBuilder();
        appendContext2(clientOperationId, operationId, msg);
        appendURL(request, msg);

        if (this.responsePretty) {
            msg.append(PRETTY_IN).append(SPACE);
        } else {
            msg.append(", ");
        }
        msg.append("client-execution-time: ").append(clientExecutionTime).append("ms");

        if (response != null) {
            if (this.responsePretty) {
                msg.append(PRETTY_IN).append(SPACE);
            }
            msg.append("status: ").append(response.getStatusCode().value());

            appendHeaders(response, msg);

            appendBody(response, msg);
        } else {
            if (this.responsePretty) {
                msg.append(PRETTY_IN).append(SPACE);
            }
            msg.append("NO RICEVUTA");
        }

        return msg.toString();
    }

    private void appendBody(ClientHttpResponse response, StringBuilder msg) throws IOException {
        if (this.responseIncludePayload) {
            String payload = bodyToString(response.getBody());
            if (!payload.isBlank()) {
                if (this.requestPretty) {
                    msg.append(PRETTY_IN).append(SPACE);
                } else {
                    msg.append(", ");
                }
                msg.append("payload: ").append(payload);
            }
        }
    }

    private void appendHeaders(ClientHttpResponse response, StringBuilder msg) {
        if (this.responseIncludeHeaders) {
            HttpHeaders headers = new HttpHeaders();
            response.getHeaders().forEach((s, h) -> headers.add(s, StringUtils.join(h, ",")));
            maskSensitiveHeader(this.responseHeaderPredicate, headers);
            String formatResponseHeaders = formatResponseHeaders(headers);
            if (formatResponseHeaders != null) {
                if (this.requestPretty) {
                    msg.append(PRETTY_IN).append(SPACE);
                } else {
                    msg.append(", ");
                }
                msg.append("headers: [").append(formatResponseHeaders);
                if (this.requestPretty) {
                    msg.append(PRETTY_OUT).append(SPACE);
                }
                msg.append("]");
            }
        }
    }

    protected void logRequest(
            String clientOperationId, String operationId, HttpRequest request, byte[] reqBody) {
        if (log.isDebugEnabled()) {
            log.debug(createRequestMessage(clientOperationId, operationId, request, reqBody));
        }
    }

    @SneakyThrows
    protected void logResponse(
            String clientOperationId,
            String operationId,
            String clientExecutionTime,
            HttpRequest request,
            ClientHttpResponse response) {
        if (log.isDebugEnabled()) {
            log.debug(
                    createResponseMessage(
                            clientOperationId, operationId, clientExecutionTime, request, response));
        }
    }

    private String formatRequestHeaders(MultiValueMap<String, String> headers) {
        Stream<String> stream =
                headers.entrySet().stream()
                        .map(
                                entry -> {
                                    String values =
                                            entry.getValue().stream()
                                                    .collect(Collectors.joining(DELIMITER, "\"", "\""));
                                    if (this.requestPretty) {
                                        return PRETTY_OUT + "*\t" + entry.getKey() + ": [" + values + "]";
                                    } else {
                                        return entry.getKey() + ": [" + values + "]";
                                    }
                                });
        if (this.requestPretty) {
            return stream.collect(Collectors.joining(""));
        } else {
            return stream.collect(Collectors.joining(", "));
        }
    }

    private String formatResponseHeaders(MultiValueMap<String, String> headers) {
        Stream<String> stream =
                headers.entrySet().stream()
                        .map(
                                entry -> {
                                    String values =
                                            entry.getValue().stream()
                                                    .collect(Collectors.joining(DELIMITER, "\"", "\""));
                                    if (this.responsePretty) {
                                        return PRETTY_IN + "*\t" + entry.getKey().toLowerCase() + ": [" + values + "]";
                                    } else {
                                        return entry.getKey().toLowerCase() + ": [" + values + "]";
                                    }
                                });
        if (this.requestPretty) {
            return stream.collect(Collectors.joining(""));
        } else {
            return stream.collect(Collectors.joining(", "));
        }
    }

    private String bodyToString(InputStream body) throws IOException {
        return StreamUtils.copyToString(body, StandardCharsets.UTF_8);
    }

    protected void avoidEventPersistenceOnRE() {
        this.mustPersistEventOnRE = false;
    }

    private void generateRe(
            HttpRequest request,
            byte[] body,
            ClientHttpResponse response,
            WorkflowStatus status,
            OutcomeEnum outcome)
            throws IOException {
        ReRequestContext requestContext = null;
        if (request != null) {
            requestContext =
                    ReRequestContext.builder()
                            .uri(request.getURI().toString())
                            .method(request.getMethod())
                            .headers(request.getHeaders())
                            .payload(new String(body))
                            .build();
        }
        ReResponseContext responseContext = null;
        if (response != null) {
            responseContext =
                    ReResponseContext.builder()
                            .statusCode(HttpStatus.valueOf(response.getStatusCode().value()))
                            .headers(response.getHeaders())
                            .payload(StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8))
                            .build();
        }
        if (this.mustPersistEventOnRE) {
            setInMDCAfterClientResponse(responseContext != null ? responseContext.getPayload() : "");
            reService.sendEvent(status, null, outcome, requestContext, responseContext);
            deleteFromMDCAfterClientResponse();
        }
    }

    protected abstract WorkflowStatus getOperationStatus(String url, HttpMethod httpMethod);

    protected abstract void setInMDCAfterClientResponse(String payload);

    protected abstract void deleteFromMDCAfterClientResponse();
}
