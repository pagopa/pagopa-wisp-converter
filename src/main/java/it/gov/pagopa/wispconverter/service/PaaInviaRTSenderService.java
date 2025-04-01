package it.gov.pagopa.wispconverter.service;

import gov.telematici.pagamenti.ws.papernodo.EsitoPaaInviaRT;
import gov.telematici.pagamenti.ws.papernodo.FaultBean;
import gov.telematici.pagamenti.ws.papernodo.PaaInviaRTRisposta;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.model.enumz.OutcomeEnum;
import it.gov.pagopa.wispconverter.repository.model.enumz.ReceiptStatusEnum;
import it.gov.pagopa.wispconverter.repository.model.enumz.WorkflowStatus;
import it.gov.pagopa.wispconverter.service.model.re.ReRequestContext;
import it.gov.pagopa.wispconverter.service.model.re.ReResponseContext;
import it.gov.pagopa.wispconverter.util.Constants;
import it.gov.pagopa.wispconverter.util.JaxbElementUtil;
import it.gov.pagopa.wispconverter.util.ProxyUtility;
import jakarta.xml.soap.SOAPMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaaInviaRTSenderService {

    private final RestClient.Builder restClientBuilder;

    private final ReService reService;

    private final RtReceiptCosmosService rtReceiptCosmosService;

    private final JaxbElementUtil jaxbElementUtil;

    @Value("#{'${wisp-converter.rt-send.no-dead-letter-on-states}'.split(',')}")
    private List<String> noDeadLetterOnStates;

    public static HttpHeaders toHttpHeaders(List<Pair<String, String>> headerList) {
        HttpHeaders headers = new HttpHeaders();
        for (Pair<String, String> pair : headerList) {
            headers.add(pair.getFirst(), pair.getSecond());
        }
        return headers;
    }

    /**
     * This method wraps an exception in a {@link AppException}
     *
     * @param e An exception
     * @return an {@link AppException}
     */
    private static AppException wrapInAppException(Exception e) {
        if (e instanceof AppException appException) {
            return appException;
        } else {
            return new AppException(AppErrorCodeMessageEnum.RECEIPT_GENERATION_GENERIC_ERROR, e);
        }
    }

    public void sendToCreditorInstitution(URI uri, InetSocketAddress proxyAddress, List<Pair<String, String>> headers, String payload, String domainId, String iuv, String ccp) {
        try {
            callCreditorInstitution(uri, proxyAddress, headers, payload, domainId, iuv, ccp);
        } catch (Exception e) {
            // Save an RE event in order to track the response from creditor institution
            throw wrapInAppException(e);
        }
    }

    private void callCreditorInstitution(URI uri, InetSocketAddress proxyAddress, List<Pair<String, String>> headers, String payload, String domainId, String iuv, String ccp) throws NoSuchAlgorithmException, KeyManagementException {
        // Generating the REST client, setting proxy specification if needed
        RestClient client = generateClient(proxyAddress);

        // Send the passed request payload to the passed URL
        RestClient.RequestBodySpec bodySpec = client.post()
                .uri(uri)
                .body(payload);
        for (Pair<String, String> header : headers) {
            bodySpec.header(header.getFirst(), header.getSecond());
        }

        rtReceiptCosmosService.updateReceiptStatus(domainId, iuv, ccp, ReceiptStatusEnum.SENDING);

        // Retrieving response from creditor institution paaInviaRT response
        ResponseEntity<String> response = executeCall(uri, headers, payload, bodySpec);

        String bodyPayload = response.getBody();
        // check SOAP response and extract body if it is valid
        PaaInviaRTRisposta body = checkResponseValidity(response, bodyPayload);

        // check the response and if the outcome is KO, throw an exception
        EsitoPaaInviaRT paaInviaRTRisposta = body.getPaaInviaRTRisposta();
        // check the response if the dead letter sending is needed
        boolean isSavedDeadLetter = checkIfSendDeadLetter(paaInviaRTRisposta);

        // set the correct response regarding the creditor institution response
        if (Constants.OK.equals(paaInviaRTRisposta.getEsito())) {
            rtReceiptCosmosService.updateReceiptStatus(domainId, iuv, ccp, ReceiptStatusEnum.SENT);
            MDC.put(Constants.MDC_OUTCOME, OutcomeEnum.OK.name());
            reService.sendEvent(
                    WorkflowStatus.COMMUNICATION_WITH_CREDITOR_INSTITUTION_PROCESSED,
                    null,
                    OutcomeEnum.OK,
                    ReRequestContext.builder()
                            .method(HttpMethod.POST)
                            .uri(uri.toString())
                            .headers(toHttpHeaders(headers))
                            .payload(payload)
                            .build(),
                    ReResponseContext.builder()
                            .headers(response.getHeaders())
                            .statusCode(HttpStatus.valueOf(response.getStatusCode().value()))
                            .payload(response.getBody())
                            .build());
        } else {
            rtReceiptCosmosService.updateReceiptStatus(domainId, iuv, ccp, ReceiptStatusEnum.SENT_REJECTED_BY_EC);
            MDC.put(Constants.MDC_OUTCOME, OutcomeEnum.SENDING_RT_FAILED_REJECTED_BY_CI.name());
            reService.sendEvent(
                    WorkflowStatus.COMMUNICATION_WITH_CREDITOR_INSTITUTION_PROCESSED,
                    "Creditor Institution refused RT",
                    OutcomeEnum.COMMUNICATION_FAILURE,
                    ReRequestContext.builder()
                            .method(HttpMethod.POST)
                            .uri(uri.toString())
                            .headers(toHttpHeaders(headers))
                            .payload(payload)
                            .build(),
                    ReResponseContext.builder()
                            .headers(response.getHeaders())
                            .statusCode(HttpStatus.valueOf(response.getStatusCode().value()))
                            .payload(response.getBody())
                            .build());
            FaultBean fault = paaInviaRTRisposta.getFault();
            String faultCode = "ND";
            String faultString = "ND";
            String faultDescr = "ND";
            if (fault != null) {
                faultCode = fault.getFaultCode();
                faultString = fault.getFaultString();
                faultDescr = fault.getDescription();
            }
            if (isSavedDeadLetter) {
                // throw to move receipt to dead letter container
                throw new AppException(AppErrorCodeMessageEnum.RECEIPT_GENERATION_ERROR_DEAD_LETTER, paaInviaRTRisposta.getEsito(), faultCode, faultString, faultDescr);
            } else {
                throw new AppException(AppErrorCodeMessageEnum.RECEIPT_GENERATION_ERROR_RESPONSE_FROM_CREDITOR_INSTITUTION, faultCode, faultString, faultDescr);
            }
        }
    }

    private ResponseEntity<String> executeCall(URI uri, List<Pair<String, String>> headers, String payload, RestClient.RequestBodySpec bodySpec) {
        ResponseEntity<String> response;
        try {
            response = bodySpec.retrieve().toEntity(String.class);
        } catch (HttpStatusCodeException error) {
            ResponseEntity<String> res = new ResponseEntity<>(error.getResponseBodyAsString(), error.getResponseHeaders(), error.getStatusCode().value());
            reService.sendEvent(
                    WorkflowStatus.COMMUNICATION_WITH_CREDITOR_INSTITUTION_PROCESSED,
                    "HTTP error on communication with creditor institution",
                    OutcomeEnum.COMMUNICATION_FAILURE,
                    ReRequestContext.builder()
                            .method(HttpMethod.POST)
                            .uri(uri.toString())
                            .headers(toHttpHeaders(headers))
                            .payload(payload)
                            .build(),
                    ReResponseContext.builder()
                            .headers(res.getHeaders())
                            .statusCode(HttpStatus.valueOf(res.getStatusCode().value()))
                            .build());
            throw error;
        } catch (Exception e) {
            reService.sendEvent(
                    WorkflowStatus.COMMUNICATION_WITH_CREDITOR_INSTITUTION_PROCESSED,
                    "Unexpected error: " + e.getMessage(),
                    OutcomeEnum.COMMUNICATION_FAILURE,
                    ReRequestContext.builder()
                            .method(HttpMethod.POST)
                            .uri(uri.toString())
                            .headers(toHttpHeaders(headers))
                            .payload(payload)
                            .build(), null);
            throw e;
        }
        return response;
    }

    private boolean checkIfSendDeadLetter(EsitoPaaInviaRT esitoPaaInviaRT) {
        return esitoPaaInviaRT.getFault() == null ||
                (esitoPaaInviaRT.getFault() != null && !noDeadLetterOnStates.contains(esitoPaaInviaRT.getFault().getFaultCode()));
    }

    private RestClient generateClient(InetSocketAddress proxyAddress) throws NoSuchAlgorithmException, KeyManagementException {
        // Generating the REST client, setting proxy specification if needed
        RestClient client;
        if (proxyAddress != null) {
            client = RestClient.builder(ProxyUtility.getProxiedClient(proxyAddress)).build();
        } else {
            client = restClientBuilder.build();
        }
        return client;
    }

    private PaaInviaRTRisposta checkResponseValidity(ResponseEntity<String> response, String rawBody) {

        // check the response received and, if the status code is not a 2xx code, it throws an exception
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AppException(AppErrorCodeMessageEnum.CLIENT_PAAINVIART, "Error response: " + response.getStatusCode().value());
        }
        // validating the response body and, if something is null, throw an exception
        if (rawBody == null) {
            throw new AppException(AppErrorCodeMessageEnum.RECEIPT_GENERATION_WRONG_RESPONSE_FROM_CREDITOR_INSTITUTION, "Passed null body");
        }
        SOAPMessage soapMessage = jaxbElementUtil.getMessage(rawBody.getBytes(StandardCharsets.UTF_8));
        PaaInviaRTRisposta body = this.jaxbElementUtil.getBody(soapMessage, PaaInviaRTRisposta.class);
        if (body.getPaaInviaRTRisposta() == null) {
            throw new AppException(AppErrorCodeMessageEnum.RECEIPT_GENERATION_WRONG_RESPONSE_FROM_CREDITOR_INSTITUTION, String.format("Passed null paaInviaRTRisposta tag. Body: [%s]", rawBody));
        }

        return body;
    }
}
