package it.gov.pagopa.wispconverter.service;

import gov.telematici.pagamenti.ws.papernodo.EsitoPaaInviaRT;
import gov.telematici.pagamenti.ws.papernodo.FaultBean;
import gov.telematici.pagamenti.ws.papernodo.PaaInviaRTRisposta;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.model.enumz.ClientEnum;
import it.gov.pagopa.wispconverter.repository.model.enumz.OutcomeEnum;
import it.gov.pagopa.wispconverter.service.model.re.ReEventDto;
import it.gov.pagopa.wispconverter.util.Constants;
import it.gov.pagopa.wispconverter.util.JaxbElementUtil;
import it.gov.pagopa.wispconverter.util.ReUtil;
import jakarta.xml.soap.SOAPMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaaInviaRTSenderService {

    private final RestClient.Builder restClientBuilder;

    private final ReService reService;

    private final JaxbElementUtil jaxbElementUtil;

    public void sendToCreditorInstitution(String url, List<Pair<String, String>> headers, String payload) {

        try {

            // Generating the REST client and send the passed request payload to the passed URL
            RestClient client = restClientBuilder.build();
            RestClient.RequestBodySpec bodySpec = client.post()
                    .uri(URI.create(url))
                    .body(payload);
            for (Pair<String, String> header : headers) {
                bodySpec.header(header.getFirst(), header.getSecond());
            }

            // Save an RE event in order to track the communication with creditor institution
            generateREForRequestToCreditorInstitution(url, headers, payload);

            // Communicating with creditor institution sending the paaInviaRT request
            ResponseEntity<String> response = bodySpec.retrieve().toEntity(String.class);

            // check SOAP response and extract body if it is valid
            String bodyPayload = response.getBody();
            PaaInviaRTRisposta body = checkResponseValidity(response, bodyPayload);

            // Save an RE event in order to track the response from creditor institution
            generateREForResponseFromCreditorInstitution(url, response.getStatusCode().value(), response.getHeaders(), bodyPayload, OutcomeEnum.RECEIVED);

            // check the response and if the outcome is KO, throw an exception
            EsitoPaaInviaRT esitoPaaInviaRT = body.getPaaInviaRTRisposta();
            if (Constants.KO.equals(esitoPaaInviaRT.getEsito())) {
                FaultBean fault = esitoPaaInviaRT.getFault();
                String faultCode = "ND";
                String faultString = "ND";
                String faultDescr = "ND";
                if (fault != null) {
                    faultCode = fault.getFaultCode();
                    faultString = fault.getFaultString();
                    faultDescr = fault.getDescription();
                }

                throw new AppException(AppErrorCodeMessageEnum.RECEIPT_GENERATION_ERROR_RESPONSE_FROM_CREDITOR_INSTITUTION, faultCode, faultString, faultDescr);
            }

        } catch (AppException e) {

            throw e;

        } catch (Exception e) {

            // Save an RE event in order to track the response from creditor institution
            if (e instanceof HttpClientErrorException httpClientErrorException) {

                int statusCode = httpClientErrorException.getStatusCode().value();
                String responseBody = httpClientErrorException.getResponseBodyAsString();
                generateREForResponseFromCreditorInstitution(url, statusCode, httpClientErrorException.getResponseHeaders(), responseBody, OutcomeEnum.RECEIVED_FAILURE);
            }

            throw new AppException(AppErrorCodeMessageEnum.RECEIPT_GENERATION_GENERIC_ERROR, e);
        }
    }


    private PaaInviaRTRisposta checkResponseValidity(ResponseEntity<String> response, String rawBody) {

        // check the response received and, if is a 4xx or a 5xx HTTP error code throw an exception
        if (response.getStatusCode().is4xxClientError() || response.getStatusCode().is5xxServerError()) {
            throw new AppException(AppErrorCodeMessageEnum.CLIENT_PAAINVIART, "Error response: " + response.getStatusCode().value());
        }
        // validating the response body and, if something is null, throw an exception
        if (rawBody == null) {
            throw new AppException(AppErrorCodeMessageEnum.RECEIPT_GENERATION_WRONG_RESPONSE_FROM_CREDITOR_INSTITUTION, "Passed null body");
        }
        SOAPMessage soapMessage = jaxbElementUtil.getMessage(rawBody.getBytes(StandardCharsets.UTF_8));
        PaaInviaRTRisposta body = this.jaxbElementUtil.getBody(soapMessage, PaaInviaRTRisposta.class);
        if (body.getPaaInviaRTRisposta() == null) {
            throw new AppException(AppErrorCodeMessageEnum.RECEIPT_GENERATION_WRONG_RESPONSE_FROM_CREDITOR_INSTITUTION, "Passed null paaInviaRTRisposta tag");
        }

        return body;
    }


    private void generateREForRequestToCreditorInstitution(String uri, List<Pair<String, String>> headers, String body) {

        StringBuilder headerBuilder = new StringBuilder();
        headers.forEach(header -> headerBuilder.append(", ").append(header.getFirst()).append(": [\"").append(header.getSecond()).append("\"]"));

        // setting data in MDC for next use
        ReEventDto reEvent = ReUtil.createREForClientInterfaceInRequestEvent("POST", uri, headerBuilder.toString(), body, ClientEnum.CREDITOR_INSTITUTION_ENDPOINT, OutcomeEnum.SEND);
        reService.addRe(reEvent);
    }

    private void generateREForResponseFromCreditorInstitution(String uri, int httpStatus, HttpHeaders headers, String body, OutcomeEnum outcome) {

        // setting data in MDC for next use
        ReEventDto reEvent = ReUtil.createREForClientInterfaceInResponseEvent("POST", uri, headers, httpStatus, body, ClientEnum.CREDITOR_INSTITUTION_ENDPOINT, outcome);
        reService.addRe(reEvent);
    }

}
