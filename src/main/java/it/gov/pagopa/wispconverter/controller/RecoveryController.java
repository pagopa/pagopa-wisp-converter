package it.gov.pagopa.wispconverter.controller;

import com.azure.core.annotation.QueryParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.gov.pagopa.wispconverter.controller.model.*;
import it.gov.pagopa.wispconverter.repository.model.enumz.WorkflowStatus;
import it.gov.pagopa.wispconverter.service.RecoveryService;
import it.gov.pagopa.wispconverter.util.EndpointRETrace;
import it.gov.pagopa.wispconverter.util.ErrorUtil;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static it.gov.pagopa.wispconverter.util.CommonUtility.sanitizeInput;

@RestController
@RequestMapping("/recovery")
@Validated
@RequiredArgsConstructor
@Tag(name = "Recovery", description = "Recovery and reconciliation APIs")
@Slf4j
public class RecoveryController {

    private static final String BP_RECONCILIATION_OK_BY_SESSIONID = "reconciliation-rt-ok-by-sessionid";
    private static final String BP_RECONCILIATION_KO_BY_SESSIONID = "reconciliation-rt-ko-by-sessionid";
    private static final String BP_RECONCILIATION_KO_BY_PAYLOAD = "reconciliation-rt-ko-by-payload";

    private final RecoveryService recoveryService;

    private final ErrorUtil errorUtil;

    @Operation(summary = "Execute IUV reconciliation for certain creditor institution.", description = "Execute reconciliation of all IUVs for certain creditor institution, sending RT for close payment.", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"Recovery"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Started reconciling IUVs with explicit RT send")
    })
    @PostMapping(value = "/{creditor_institution}/receipt-ko")
    public ResponseEntity<RecoveryReceiptResponse> recoverReceiptKOForCreditorInstitution(@PathVariable("creditor_institution") String ci, @QueryParam("date_from") String dateFrom, @QueryParam("date_to") String dateTo) {
        RecoveryReceiptResponse response = recoveryService.recoverReceiptKOByCI(ci, dateFrom, dateTo);
        return ResponseEntity.ok(response);
    }


    @Operation(summary = "Execute IUV reconciliation for certain creditor institution.", description = "Execute reconciliation of all IUVs for certain creditor institution, sending RT for close payment.", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"Recovery"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Completed IUV reconciliation with explicit RT submission"),
            @ApiResponse(responseCode = "400", description = "It is not possible to complete reconciliation (with explicit RT submission) for the submitted UIV")
    })
    @PostMapping(value = "/{creditor_institution}/rpt/{iuv}/receipt-ko")
    public ResponseEntity<RecoveryReceiptResponse> recoverReceiptKOForCreditorInstitutionAndIUV(@Pattern(regexp = "[a-zA-Z0-9_-]{1,100}") @PathVariable("creditor_institution") String ci,
                                                                                                @Pattern(regexp = "[a-zA-Z0-9_-]{1,100}") @PathVariable("iuv") String iuv,
                                                                                                @Pattern(regexp = "[a-zA-Z0-9_-]{1,10}") @QueryParam("date_from") String dateFrom,
                                                                                                @Pattern(regexp = "[a-zA-Z0-9_-]{1,10}") @QueryParam("date_to") String dateTo) {

        RecoveryReceiptResponse recoveryReceiptResponse = recoveryService.recoverReceiptKOByIUV(ci, iuv, dateFrom, dateTo);
        if (recoveryReceiptResponse != null) {
            return ResponseEntity.ok(recoveryReceiptResponse);
        } else {
            // RPT with CI and IUV could not be recovered via API
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Execute reconciliation for passed receipts.", description = "Execute reconciliation of all receipts in the request, searching by passed identifier", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"Recovery"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Reconciliation scheduled")
    })
    @PostMapping(value = "/receipts")
    public ResponseEntity<RecoveryReceiptReportResponse> recoverReceiptToBeReSent(@RequestBody RecoveryReceiptRequest request) {
        return ResponseEntity.ok(recoveryService.recoverReceiptToBeReSent(request));
    }

    @Operation(summary = "Execute reconciliation for passed receipts by partition.", description = "Execute reconciliation of all receipts contained in the partitions of the request", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"Recovery"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Reconciliation scheduled")
    })
    @PostMapping(value = "/partitions")
    public ResponseEntity<RecoveryReceiptReportResponse> recoverReceiptToBeReSentByPartition(@RequestBody RecoveryReceiptByPartitionRequest request) {
        log.debug("Invoking API operation recoverReceiptToBeReSentByPartition - args: {}", sanitizeInput(request.toString()));
        return ResponseEntity.ok(recoveryService.recoverReceiptToBeReSentByPartition(request));
    }

    @Operation(summary = "Execute reconciliation for OK receipts by sessionId.", description = "Execute reconciliation of all OK receipts related to the sessionIds of the request", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"Recovery"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Reconciliation scheduled")
    })
    @PostMapping(value = "/sessionIds/ok")
    @EndpointRETrace(status = WorkflowStatus.RT_RECONCILIATION_PROCESSED, businessProcess = BP_RECONCILIATION_OK_BY_SESSIONID, reEnabled = true)
    public ResponseEntity<RecoveryReceiptReportResponse> recoverOkReceiptToBeReSentBySessionId(@RequestBody RecoveryReceiptBySessionIdRequest request) {
        log.debug("Invoking API operation recoverOkReceiptToBeReSentBySessionId - args: {}", sanitizeInput(request.toString()));
        return ResponseEntity.ok(recoveryService.recoverReceiptOkToBeReSentBySessionIds(request));
    }

    @Operation(summary = "Execute reconciliation for KO receipts by sessionId.", description = "Execute reconciliation of all KO receipts related to the sessionIds of the request", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"Recovery"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Reconciliation scheduled")
    })
    @PostMapping(value = "/sessionIds/ko")
    @EndpointRETrace(status = WorkflowStatus.RT_RECONCILIATION_PROCESSED, businessProcess = BP_RECONCILIATION_KO_BY_SESSIONID, reEnabled = true)
    public ResponseEntity<Void> recoverKoReceiptToBeReSentBySessionId(@RequestBody RecoveryReceiptBySessionIdRequest request) {
        log.debug("Invoking API operation recoverKoReceiptToBeReSentBySessionId - args: {}", sanitizeInput(request.toString()));
        recoveryService.recoverReceiptKoToBeReSentBySessionIds(request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Execute reconciliation for KO receipts by sessionId.", description = "Execute reconciliation of all KO receipts related to the sessionIds of the request", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"Recovery"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Reconciliation scheduled")
    })
    @PostMapping(value = "/sessionIds/ko-by-request")
    @EndpointRETrace(status = WorkflowStatus.RT_RECONCILIATION_PROCESSED, businessProcess = BP_RECONCILIATION_KO_BY_PAYLOAD, reEnabled = true)
    public ResponseEntity<Void> recoverKoReceipt(@RequestBody RecoveryReceiptByPayloadRequest request) {
        log.debug("Invoking API operation recoverKoReceipt - args: {}", sanitizeInput(request.toString()));
        recoveryService.recoverReceiptKoToBeReSentByRPTRequestEntity(request);
        return ResponseEntity.ok().build();
    }
}
