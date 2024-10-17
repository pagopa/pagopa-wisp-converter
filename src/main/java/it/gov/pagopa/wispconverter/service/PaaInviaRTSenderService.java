package it.gov.pagopa.wispconverter.service;

import gov.telematici.pagamenti.ws.papernodo.EsitoPaaInviaRT;
import gov.telematici.pagamenti.ws.papernodo.FaultBean;
import gov.telematici.pagamenti.ws.papernodo.PaaInviaRTRisposta;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.model.enumz.ClientEnum;
import it.gov.pagopa.wispconverter.repository.model.enumz.InternalStepStatus;
import it.gov.pagopa.wispconverter.repository.model.enumz.OutcomeEnum;
import it.gov.pagopa.wispconverter.repository.model.enumz.ReceiptStatusEnum;
import it.gov.pagopa.wispconverter.service.model.re.ReEventDto;
import it.gov.pagopa.wispconverter.util.Constants;
import it.gov.pagopa.wispconverter.util.JaxbElementUtil;
import it.gov.pagopa.wispconverter.util.ProxyUtility;
import it.gov.pagopa.wispconverter.util.ReUtil;
import jakarta.xml.soap.SOAPMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpHeaders;
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

    public void sendToCreditorInstitution(URI uri, InetSocketAddress proxyAddress, List<Pair<String, String>> headers, String payload, String domainId, String iuv, String ccp) {

        try {

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

            // Save an RE event in order to track the communication with creditor institution
            generateREForRequestToCreditorInstitution(uri.toString(), headers, payload);

            // Retrieving response from creditor institution paaInviaRT response
            ResponseEntity<String> response = bodySpec.retrieve().toEntity(String.class);
            String bodyPayload = response.getBody();

            // Save an RE event in order to track the response from creditor institution
            generateREForResponseFromCreditorInstitution(uri.toString(), response.getStatusCode().value(), response.getHeaders(), bodyPayload, OutcomeEnum.RECEIVED, null);

            // check SOAP response and extract body if it is valid
            PaaInviaRTRisposta body = checkResponseValidity(response, bodyPayload);

            // check the response and if the outcome is KO, throw an exception
            EsitoPaaInviaRT paaInviaRTRisposta = body.getPaaInviaRTRisposta();
            // check the response if the dead letter sending is needed
            boolean isSavedDeadLetter = checkIfSendDeadLetter(paaInviaRTRisposta);

            // set the correct response regarding the creditor institution response
            if (Constants.OK.equals(paaInviaRTRisposta.getEsito())) {
                rtReceiptCosmosService.updateReceiptStatus(domainId, iuv, ccp, ReceiptStatusEnum.SENT);
                reService.addRe(ReUtil.getREBuilder().status(InternalStepStatus.RT_SENT_OK).build());
            } else {
                rtReceiptCosmosService.updateReceiptStatus(domainId, iuv, ccp, ReceiptStatusEnum.SENT_REJECTED_BY_EC);
                FaultBean fault = paaInviaRTRisposta.getFault();
                String faultCode = "ND";
                String faultString = "ND";
                String faultDescr = "ND";
                if (fault != null) {
                    faultCode = fault.getFaultCode();
                    faultString = fault.getFaultString();
                    faultDescr = fault.getDescription();
                }
                if(isSavedDeadLetter) {
                    // throw to move receipt to dead letter container
                    throw new AppException(AppErrorCodeMessageEnum.RECEIPT_GENERATION_ERROR_DEAD_LETTER, paaInviaRTRisposta.getEsito(), faultCode, faultString, faultDescr);
                }
                throw new AppException(AppErrorCodeMessageEnum.RECEIPT_GENERATION_ERROR_RESPONSE_FROM_CREDITOR_INSTITUTION, faultCode, faultString, faultDescr);
            }

        } catch (AppException e) {

            throw e;

        } catch (Exception e) {

            // Save an RE event in order to track the response from creditor institution
            if (e instanceof HttpStatusCodeException error) {

                int statusCode = error.getStatusCode().value();
                String responseBody = error.getResponseBodyAsString();
                String otherInfo = error.getStatusText();
                generateREForResponseFromCreditorInstitution(uri.toString(), statusCode, error.getResponseHeaders(), responseBody, OutcomeEnum.RECEIVED_FAILURE, otherInfo);
            }

            throw new AppException(AppErrorCodeMessageEnum.RECEIPT_GENERATION_GENERIC_ERROR, e.getMessage());
        }
    }

    private boolean checkIfSendDeadLetter (EsitoPaaInviaRT esitoPaaInviaRT) {
        return esitoPaaInviaRT.getFault() == null ||
                (esitoPaaInviaRT.getFault() != null && !noDeadLetterOnStates.contains(esitoPaaInviaRT.getFault().getFaultCode()));
    }

    private RestClient generateClient(InetSocketAddress proxyAddress) throws NoSuchAlgorithmException, KeyManagementException {
        // Generating the REST client, setting proxy specification if needed
        RestClient client;
        if (proxyAddress != null) {
            client = RestClient.builder(ProxyUtility.getProxiedClient(proxyAddress))
                    .build();
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

    private void generateREForRequestToCreditorInstitution(String uri, List<Pair<String, String>> headers, String body) {

        StringBuilder headerBuilder = new StringBuilder();
        headers.forEach(header -> headerBuilder.append(", ").append(header.getFirst()).append(": [\"").append(header.getSecond()).append("\"]"));

        // setting data in MDC for next use
        ReEventDto reEvent = ReUtil.createREForClientInterfaceInRequestEvent("POST", uri, headerBuilder.toString(), body, ClientEnum.CREDITOR_INSTITUTION_ENDPOINT, OutcomeEnum.SEND);
        reService.addRe(reEvent);
    }

    private void generateREForResponseFromCreditorInstitution(String uri, int httpStatus, HttpHeaders headers, String body, OutcomeEnum outcome, String otherInfo) {

        // setting data in MDC for next use
        ReEventDto reEvent = ReUtil.createREForClientInterfaceInResponseEvent("POST", uri, headers, httpStatus, body, ClientEnum.CREDITOR_INSTITUTION_ENDPOINT, outcome);
        reEvent.setInfo(otherInfo);
        reService.addRe(reEvent);
    }
}
