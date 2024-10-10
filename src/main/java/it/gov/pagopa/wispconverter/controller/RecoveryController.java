package it.gov.pagopa.wispconverter.controller;

import com.azure.core.annotation.QueryParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.gov.pagopa.wispconverter.controller.model.RecoveryReceiptByPartitionRequest;
import it.gov.pagopa.wispconverter.controller.model.RecoveryReceiptReportResponse;
import it.gov.pagopa.wispconverter.controller.model.RecoveryReceiptRequest;
import it.gov.pagopa.wispconverter.controller.model.RecoveryReceiptResponse;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.service.RecoveryService;
import it.gov.pagopa.wispconverter.util.Constants;
import it.gov.pagopa.wispconverter.util.ErrorUtil;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/recovery")
@Validated
@RequiredArgsConstructor
@Tag(name = "Recovery", description = "Recovery and reconciliation APIs")
@Slf4j
public class RecoveryController {

    private final RecoveryService recoveryService;

    private final ErrorUtil errorUtil;

    @Operation(summary = "Execute IUV reconciliation for certain creditor institution.", description = "Execute reconciliation of all IUVs for certain creditor institution, sending RT for close payment.", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"Recovery"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Started reconciling IUVs with explicit RT send")
    })
    @PostMapping(value = "/{creditor_institution}/receipt-ko")
    public ResponseEntity<RecoveryReceiptResponse> recoverReceiptKOForCreditorInstitution(@PathVariable("creditor_institution") String ci, @QueryParam("date_from") String dateFrom, @QueryParam("date_to") String dateTo) {
        try {
            log.info("Invoking API operation recoverReceiptKOForCreditorInstitution - args: {} {} {}", ci, dateFrom, dateTo);
            RecoveryReceiptResponse response = recoveryService.recoverReceiptKOByCI(ci, dateFrom, dateTo);
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            String operationId = MDC.get(Constants.MDC_OPERATION_ID);
            log.error(String.format("GenericException: operation-id=[%s]", operationId != null ? operationId : "n/a"), ex);
            AppException appException = new AppException(ex, AppErrorCodeMessageEnum.ERROR, ex.getMessage());
            ErrorResponse errorResponse = errorUtil.forAppException(appException);
            log.error("Failed API operation recoverReceiptKOForCreditorInstitution - error: {}", errorResponse);
            throw ex;
        } finally {
            log.info("Successful API operation recoverReceiptKOForCreditorInstitution");
        }
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
        try {
            log.info("Invoking API operation recoverReceiptKOForCreditorInstitution - args: {} {} {} {}", ci, iuv, dateFrom, dateTo);

            RecoveryReceiptResponse recoveryReceiptResponse = recoveryService.recoverReceiptKOByIUV(ci, iuv, dateFrom, dateTo);
            if (recoveryReceiptResponse != null) {
                return ResponseEntity.ok(recoveryReceiptResponse);
            } else {
                // RPT with CI and IUV could not be recovered via API
                return ResponseEntity.badRequest().build();
            }
        } catch (Exception ex) {
            String operationId = MDC.get(Constants.MDC_OPERATION_ID);
            log.error(String.format("GenericException: operation-id=[%s]", operationId != null ? operationId : "n/a"), ex);
            AppException appException = new AppException(ex, AppErrorCodeMessageEnum.ERROR, ex.getMessage());
            ErrorResponse errorResponse = errorUtil.forAppException(appException);
            log.error("Failed API operation recoverReceiptKOForCreditorInstitution - error: {}", errorResponse);
            throw ex;
        } finally {
            log.info("Successful API operation recoverReceiptKOForCreditorInstitution");
        }
    }

    @Operation(summary = "Execute reconciliation for passed receipts.", description = "Execute reconciliation of all receipts in the request, searching by passed identifier", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"Recovery"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Reconciliation scheduled")
    })
    @PostMapping(value = "/receipts")
    public ResponseEntity<RecoveryReceiptReportResponse> recoverReceiptToBeReSent(@RequestBody RecoveryReceiptRequest request) {
        try {
            log.info("Invoking API operation recoverReceiptToBeReSent - args: {}", request);
            return ResponseEntity.ok(recoveryService.recoverReceiptToBeReSent(request));
        } catch (Exception ex) {
            String operationId = MDC.get(Constants.MDC_OPERATION_ID);
            log.error(String.format("GenericException: operation-id=[%s]", operationId != null ? operationId : "n/a"), ex);
            AppException appException = new AppException(ex, AppErrorCodeMessageEnum.ERROR, ex.getMessage());
            ErrorResponse errorResponse = errorUtil.forAppException(appException);
            log.error("Failed API operation recoverReceiptToBeReSent - error: {}", errorResponse);
            throw ex;
        }
    }

    @Operation(summary = "Execute reconciliation for passed receipts by partition.", description = "Execute reconciliation of all receipts contained in the partitions of the request", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"Recovery"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Reconciliation scheduled")
    })
    @PostMapping(value = "/partitions")
    public ResponseEntity<RecoveryReceiptReportResponse> recoverReceiptToBeReSentByPartition(@RequestBody RecoveryReceiptByPartitionRequest request) {
        try {
            log.info("Invoking API operation recoverReceiptToBeReSentByPartition - args: {}", request);
            return ResponseEntity.ok(recoveryService.recoverReceiptToBeReSentByPartition(request));
        } catch (Exception ex) {
            String operationId = MDC.get(Constants.MDC_OPERATION_ID);
            log.error(String.format("GenericException: operation-id=[%s]", operationId != null ? operationId : "n/a"), ex);
            AppException appException = new AppException(ex, AppErrorCodeMessageEnum.ERROR, ex.getMessage());
            ErrorResponse errorResponse = errorUtil.forAppException(appException);
            log.error("Failed API operation recoverReceiptToBeReSentByPartition - error: {}", errorResponse);
            throw ex;
        }
    }
}
