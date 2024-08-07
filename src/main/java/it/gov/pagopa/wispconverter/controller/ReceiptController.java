package it.gov.pagopa.wispconverter.controller;

import com.azure.core.annotation.QueryParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.gov.pagopa.wispconverter.controller.model.ReceiptRequest;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.service.ReceiptService;
import it.gov.pagopa.wispconverter.service.RtReceiptCosmosService;
import it.gov.pagopa.wispconverter.service.model.ReceiptDto;
import it.gov.pagopa.wispconverter.util.Constants;
import it.gov.pagopa.wispconverter.util.ErrorUtil;
import it.gov.pagopa.wispconverter.util.Trace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/receipt")
@Validated
@RequiredArgsConstructor
@Tag(name = "Receipt", description = "Convert sendPaymentResultV2, closePaymentV2 or paSendRTV2 into paaInviaRT to EC")
@Slf4j
public class ReceiptController {

    private static final String BP_RECEIPT_OK = "receipt-ok";
    private static final String BP_RECEIPT_KO = "receipt-ko";
    private static final String BP_RECEIPT_RETRIEVE = "receipt-retrieve";

    private final ReceiptService receiptService;

    private final RtReceiptCosmosService rtReceiptCosmosService;

    private final ObjectMapper mapper;

    private final ErrorUtil errorUtil;

    @Operation(summary = "", description = "", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"Receipt"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Receipt exists", content = @Content(schema = @Schema()))
    })
    @PostMapping(
            value = "",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    @Trace(businessProcess = BP_RECEIPT_RETRIEVE, reEnabled = true)
    public ResponseEntity<String> receiptRetrieve(@QueryParam("ci") String ci, @QueryParam("ccp") String ccp, @QueryParam("iuv") String iuv) {
        try {
            log.info("Invoking API operation receiptRetrieve - args: {}", ci, ccp, iuv);
            if(rtReceiptCosmosService.receiptRtExist(ci, ccp, iuv))
                return ResponseEntity.ok("");
            else return ResponseEntity.notFound().build();
            log.info("Successful API operation receiptRetrieve");
        } catch (Exception ex) {
            String operationId = MDC.get(Constants.MDC_OPERATION_ID);
            log.error(String.format("GenericException: operation-id=[%s]", operationId != null ? operationId : "n/a"), ex);

            AppException appException = new AppException(ex, AppErrorCodeMessageEnum.ERROR, ex.getMessage());
            ErrorResponse errorResponse = errorUtil.forAppException(appException);
            log.error("Failed API operation receiptRetrieve - error: {}", errorResponse);
            throw ex;
        }
    }

    @Operation(summary = "", description = "", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"Receipt"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully forwarded negative paaInviaRT to EC", content = @Content(schema = @Schema()))
    })
    @PostMapping(
            value = "/ko",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    @Trace(businessProcess = BP_RECEIPT_KO, reEnabled = true)
    public void receiptKo(@RequestBody String request) throws Exception {

        try {
            log.info("Invoking API operation receiptKo - args: {}", request);
            receiptService.sendKoPaaInviaRtToCreditorInstitution(List.of(mapper.readValue(request, ReceiptDto.class)).toString());
            log.info("Successful API operation receiptKo");
        } catch (Exception ex) {
            String operationId = MDC.get(Constants.MDC_OPERATION_ID);
            log.error(String.format("GenericException: operation-id=[%s]", operationId != null ? operationId : "n/a"), ex);

            AppException appException = new AppException(ex, AppErrorCodeMessageEnum.ERROR, ex.getMessage());
            ErrorResponse errorResponse = errorUtil.forAppException(appException);
            log.error("Failed API operation receiptKo - error: {}", errorResponse);
            throw ex;
        }
    }

    @Operation(summary = "", description = "", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"Receipt"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully forwarded positive paaInviaRT to EC", content = @Content(schema = @Schema()))
    })
    @PostMapping(
            value = "/ok",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    @Trace(businessProcess = BP_RECEIPT_OK, reEnabled = true)
    public void receiptOk(@RequestBody ReceiptRequest request) throws IOException {

        try {
            log.info("Invoking API operation receiptOk - args: {}", request.toString());
            receiptService.sendOkPaaInviaRtToCreditorInstitution(request.getContent());
            log.info("Successful API operation receiptOk");
        } catch (Exception ex) {
            String operationId = MDC.get(Constants.MDC_OPERATION_ID);
            log.error(String.format("GenericException: operation-id=[%s]", operationId != null ? operationId : "n/a"), ex);

            AppException appException = new AppException(ex, AppErrorCodeMessageEnum.ERROR, ex.getMessage());
            ErrorResponse errorResponse = errorUtil.forAppException(appException);
            log.error("Failed API operation receiptOk - error: {}", errorResponse);
            throw ex;
        }
    }
}
