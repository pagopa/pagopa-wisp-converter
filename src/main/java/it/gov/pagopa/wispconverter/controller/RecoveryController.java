package it.gov.pagopa.wispconverter.controller;

import com.azure.core.annotation.QueryParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
            @ApiResponse(responseCode = "200", description = "Started reconciling IUV with explicit RT send")
    })
    @PostMapping(value = "/{creditor_institution}/rpt/{iuv}/receipt-ko")
    public ResponseEntity<String> recoverReceiptKOForCreditorInstitutionAndIUV(@Pattern(regexp = "[a-zA-Z0-9_-]{1,100}") @PathVariable("creditor_institution") String ci,
                                                                                                @Pattern(regexp = "[a-zA-Z0-9_-]{1,100}") @PathVariable("iuv") String iuv,
                                                                                                @Pattern(regexp = "[a-zA-Z0-9_-]{1,10}") @QueryParam("date_from") String dateFrom,
                                                                                                @Pattern(regexp = "[a-zA-Z0-9_-]{1,10}") @QueryParam("date_to") String dateTo) {
        try {
            log.info("Invoking API operation recoverReceiptKOForCreditorInstitution - args: {} {} {} {}", ci, iuv, dateFrom, dateTo);

            boolean recovered = recoveryService.recoverReceiptKOByIUV(ci, iuv, dateFrom, dateTo);
            if(recovered)
                return ResponseEntity.ok(String.format("RPT with CI %s and IUV %s recovered via API", ci, iuv));
            else return ResponseEntity.ok(String.format("RPT with CI %s and IUV %s could not be recovered via API", ci, iuv));
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

}
