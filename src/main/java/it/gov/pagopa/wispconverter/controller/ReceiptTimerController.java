package it.gov.pagopa.wispconverter.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.gov.pagopa.wispconverter.controller.model.ReceiptTimerRequest;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.model.enumz.InternalStepStatus;
import it.gov.pagopa.wispconverter.service.ReceiptTimerService;
import it.gov.pagopa.wispconverter.util.Constants;
import it.gov.pagopa.wispconverter.util.EndpointRETrace;
import it.gov.pagopa.wispconverter.util.ErrorUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/receipt")
@Validated
@RequiredArgsConstructor
@Tag(name = "ReceiptTimer", description = "Create and Delete payment token timer")
@Slf4j
public class ReceiptTimerController {
    private static final String BP_TIMER_SET = "timer-set";
    private static final String BP_TIMER_DELETE = "timer-delete";

    private final ReceiptTimerService receiptTimerService;

    private final ErrorUtil errorUtil;

    @Value("${wisp-converter.receipttimer-delta-activate.expirationtime.ms}")
    private Long deltaExpirationTime;


    @Operation(summary = "createTimer", description = "Create a timer linked with paymentToken and receipt data", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"ReceiptTimer"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully paymentToken expiration timer created", content = @Content(schema = @Schema()))
    })
    @PostMapping(
            value = "/timer",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    @EndpointRETrace(status = InternalStepStatus.PAYMENT_TOKEN_TIMER_CREATION_PROCESSED, businessProcess = BP_TIMER_SET, reEnabled = true)
    public void createTimer(@RequestBody ReceiptTimerRequest request) {
        try {
            log.info("Invoking API operation createTimer - args: {}", request.toString());
            // PAGOPA-2300 - the expiration time is increased by a configurable delta
            request.setExpirationTime(request.getExpirationTime() + deltaExpirationTime);
            receiptTimerService.sendMessage(request);
            log.info("Successful API operation createTimer");
        } catch (Exception ex) {
            String operationId = MDC.get(Constants.MDC_OPERATION_ID);
            log.error(String.format("GenericException: operation-id=[%s]", operationId != null ? operationId : "n/a"), ex);

            AppException appException = new AppException(ex, AppErrorCodeMessageEnum.ERROR, ex.getMessage());
            ErrorResponse errorResponse = errorUtil.forAppException(appException);
            log.error("Failed API operation createTimer - error: {}", errorResponse);
            throw ex;
        }
    }

    @Operation(summary = "deleteTimer", description = "Delete a timer by paymentToken", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"ReceiptTimer"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully paymentToken expiration timer deleted", content = @Content(schema = @Schema()))
    })
    @DeleteMapping(
            value = "/timer",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @EndpointRETrace(status = InternalStepStatus.PAYMENT_TOKEN_TIMER_DELETION_PROCESSED, businessProcess = BP_TIMER_DELETE, reEnabled = true)
    public void deleteTimer(@RequestParam() String paymentTokens) {
        try {
            log.info("Invoking API operation deleteTimer - args: {}", paymentTokens);
            List<String> tokens = Arrays.asList(paymentTokens.split(","));
            receiptTimerService.cancelScheduledMessage(tokens);
            log.info("Successful API operation deleteTimer");
        } catch (Exception ex) {
            String operationId = MDC.get(Constants.MDC_OPERATION_ID);
            log.error(String.format("GenericException: operation-id=[%s]", operationId != null ? operationId : "n/a"), ex);

            AppException appException = new AppException(ex, AppErrorCodeMessageEnum.ERROR, ex.getMessage());
            ErrorResponse errorResponse = errorUtil.forAppException(appException);
            log.error("Failed API operation deleteTimer - error: {}", errorResponse);
            throw ex;
        }
    }
}
