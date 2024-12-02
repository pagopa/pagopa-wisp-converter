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
import it.gov.pagopa.wispconverter.repository.model.enumz.OutcomeEnum;
import it.gov.pagopa.wispconverter.repository.model.enumz.WorkflowStatus;
import it.gov.pagopa.wispconverter.service.ConverterService;
import it.gov.pagopa.wispconverter.util.Constants;
import it.gov.pagopa.wispconverter.util.EndpointRETrace;
import it.gov.pagopa.wispconverter.util.ErrorUtil;
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
    @EndpointRETrace(status = WorkflowStatus.CONVERSION_PROCESSED, businessProcess = BP_REDIRECT, reEnabled = true)
    public String redirect(@Parameter(description = "", example = "identificativoIntermediarioPA_sessionId")
                           @NotBlank(message = "{redirect.session-id.not-blank}")
                           @RequestParam("idSession") String idSession,
                           HttpServletResponse response) {
        try {
            String redirectURI = converterService.convert(idSession);
            return "redirect:" + redirectURI;
        } catch (Exception e) {
            MDC.put(Constants.MDC_OUTCOME, OutcomeEnum.ERROR.name());
            AppException appEx = e instanceof AppException appException
                    ? appException
                    : new AppException(e, AppErrorCodeMessageEnum.ERROR, e.getMessage());
            ErrorResponse errorResponse = errorUtil.forAppException(appEx);
            ProblemDetail problemDetail = errorResponse.updateAndGetBody(this.messageSource, LocaleContextHolder.getLocale());
            errorUtil.finalizeError(e, problemDetail, errorResponse.getStatusCode().value());

            log.error("Failed API operation {} - error: {}", MDC.get(Constants.MDC_BUSINESS_PROCESS), errorResponse);

            return "error";
        }
    }
}
