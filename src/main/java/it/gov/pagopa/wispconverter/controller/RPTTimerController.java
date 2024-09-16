package it.gov.pagopa.wispconverter.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.gov.pagopa.wispconverter.controller.model.RPTTimerRequest;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.service.RPTTimerService;
import it.gov.pagopa.wispconverter.util.Constants;
import it.gov.pagopa.wispconverter.util.ErrorUtil;
import it.gov.pagopa.wispconverter.util.Trace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;

import static it.gov.pagopa.wispconverter.util.CommonUtility.sanitizeInput;

@RestController
@RequestMapping("/rpt")
@Validated
@RequiredArgsConstructor
@Tag(name = "RPTTimer", description = "Create and Delete rpt timer")
@Slf4j
public class RPTTimerController {
    private static final String RPT_BP_TIMER_SET = "rpt-timer-set";
    private static final String RPT_BP_TIMER_DELETE = "rpt-timer-delete";

    private final RPTTimerService rptTimerService;

    private final ErrorUtil errorUtil;


    @Operation(summary = "createTimer", description = "Create a timer linked with paymentToken and rpt data", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"RPTTimer"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully paymentToken expiration timer created", content = @Content(schema = @Schema()))
    })
    @PostMapping(
            value = "/timer",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    @Trace(businessProcess = RPT_BP_TIMER_SET, reEnabled = true)
    public void createTimer(@RequestBody RPTTimerRequest request) {
        try {
            log.info("Invoking API operation createTimer - args: {}", sanitizeInput(request.toString()));
            rptTimerService.sendMessage(request);
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
    @Trace(businessProcess = RPT_BP_TIMER_DELETE, reEnabled = true)
    public void deleteTimer(@RequestParam() String sessionId) {
        try {
            log.info("Invoking API operation deleteRPTTimer - args: {}", sanitizeInput(sessionId));
            rptTimerService.cancelScheduledMessage(sessionId);
            log.info("Successful API operation deleteRPTTimer");
        } catch (Exception ex) {
            String operationId = MDC.get(Constants.MDC_OPERATION_ID);
            log.error(String.format("GenericException: operation-id=[%s]", operationId != null ? operationId : "n/a"), ex);

            AppException appException = new AppException(ex, AppErrorCodeMessageEnum.ERROR, ex.getMessage());
            ErrorResponse errorResponse = errorUtil.forAppException(appException);
            log.error("Failed API operation deleteRPTTimer - error: {}", errorResponse);
            throw ex;
        }
    }
}