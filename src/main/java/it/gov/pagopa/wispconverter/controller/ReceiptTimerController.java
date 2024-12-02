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
import it.gov.pagopa.wispconverter.repository.model.enumz.WorkflowStatus;
import it.gov.pagopa.wispconverter.service.*;
import it.gov.pagopa.wispconverter.service.model.ReceiptDto;
import it.gov.pagopa.wispconverter.service.model.session.SessionDataDTO;
import it.gov.pagopa.wispconverter.util.EndpointRETrace;
import it.gov.pagopa.wispconverter.util.ErrorUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
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

    private final RtReceiptCosmosService rtReceiptCosmosService;

    private final RPTExtractorService rptExtractorService;

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
    @EndpointRETrace(status = WorkflowStatus.PAYMENT_TOKEN_TIMER_CREATION_PROCESSED, businessProcess = BP_TIMER_SET, reEnabled = true)
    public void createTimer(@RequestBody ReceiptTimerRequest request) {
        // PAGOPA-2300 - the expiration time is increased by a configurable delta
        request.setExpirationTime(request.getExpirationTime() + deltaExpirationTime);
        receiptTimerService.sendMessage(request);
    }

    @Operation(summary = "deleteTimer", description = "Delete a timer by paymentToken", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"ReceiptTimer"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully paymentToken expiration timer deleted", content = @Content(schema = @Schema()))
    })
    @DeleteMapping(
            value = "/timer",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @EndpointRETrace(status = WorkflowStatus.PAYMENT_TOKEN_TIMER_DELETION_PROCESSED, businessProcess = BP_TIMER_DELETE, reEnabled = true)
    public void deleteTimer(@RequestParam() String paymentTokens) {
        List<String> tokens = Arrays.asList(paymentTokens.split(","));
        try {
            // set receipts-rt status to PAYING stands for waiting SendPaymentOutcome and then paSendRTV2 (after the latter, the state will change to SENDING -> SENT)
            // Get sessionData for the first ReceiptDTO because by design session-id is equal for all paymentTokens in input
            ReceiptDto receiptDto = receiptTimerService.peek(tokens.get(0));
            if(receiptDto != null) {
                String sessionId = receiptDto.getSessionId();
                SessionDataDTO sessionDataDTO = rptExtractorService.getSessionDataFromSessionId(sessionId);
                // Update receipts-rt status to PAYING
                sessionDataDTO.getAllRPTs().forEach(rtReceiptCosmosService::updateStatusToPaying);
            }
        } catch (Exception e) {
            throw new AppException(AppErrorCodeMessageEnum.RECEIPT_RT_STATUS_TO_PAYING_FAILURE, e);
        } finally {
            // cancel scheduled message if PAYING status transition goes in exception
            receiptTimerService.cancelScheduledMessage(tokens);
        }
    }
}
