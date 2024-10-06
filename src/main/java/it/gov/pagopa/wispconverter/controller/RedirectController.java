package it.gov.pagopa.wispconverter.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.service.ConverterService;
import it.gov.pagopa.wispconverter.util.Constants;
import it.gov.pagopa.wispconverter.util.ErrorUtil;
import it.gov.pagopa.wispconverter.util.Trace;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping
@Validated
@RequiredArgsConstructor
@Tag(name = "Redirect", description = "Conversion and redirection APIs")
@Slf4j
public class RedirectController {

    private static final String BP_REDIRECT = "redirect";
    private final ConverterService converterService;
    private final ErrorUtil errorUtil;
    private final MessageSource messageSource;

    @Operation(summary = "", description = "", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"Redirect"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "302", description = "Redirect to Checkout service.", content = @Content(schema = @Schema()))
    })
    @GetMapping(value = "/payments")
    @Trace(businessProcess = BP_REDIRECT, reEnabled = true)
    public String redirect(@Parameter(description = "", example = "identificativoIntermediarioPA_sessionId")
                           @NotBlank(message = "{redirect.session-id.not-blank}")
                           @RequestParam("idSession") String idSession,
                           HttpServletResponse response) {
        try {
            log.debug("Invoking API operation redirect - args: {}", idSession.matches("[\\w\\-]*") ? idSession : "...");
            String redirectURI = converterService.convert(idSession);
            log.debug("Successful API operation redirect - result: {}", redirectURI);
            return "redirect:" + redirectURI;
        } catch (AppException appException) {
            ErrorResponse errorResponse = errorUtil.forAppException(appException);
            ProblemDetail problemDetail = errorResponse.updateAndGetBody(this.messageSource, LocaleContextHolder.getLocale());
            errorUtil.finalizeError(problemDetail, errorResponse.getStatusCode().value());
            response.setStatus(200);
            log.error("Failed API operation redirect - error: {}", errorResponse);
            return "error";
        } catch (Exception ex) {
            String operationId = MDC.get(Constants.MDC_OPERATION_ID);
            log.error(String.format("GenericException: operation-id=[%s]", operationId != null ? operationId : "n/a"), ex);

            AppException appException = new AppException(ex, AppErrorCodeMessageEnum.ERROR, ex.getMessage());
            ErrorResponse errorResponse = errorUtil.forAppException(appException);
            ProblemDetail problemDetail = errorResponse.updateAndGetBody(this.messageSource, LocaleContextHolder.getLocale());
            errorUtil.finalizeError(problemDetail, errorResponse.getStatusCode().value());
            response.setStatus(200);
            log.error("Failed API operation redirect - error: {}", errorResponse);
            return "error";
        }
    }

}
