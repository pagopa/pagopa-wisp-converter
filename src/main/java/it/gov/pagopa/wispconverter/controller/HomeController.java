package it.gov.pagopa.wispconverter.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.gov.pagopa.wispconverter.controller.model.AppInfoResponse;
import it.gov.pagopa.wispconverter.service.ConfigCacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

@RestController
@Validated
@Tag(name = "Home", description = "Application info APIs")
public class HomeController {

    @Value("${server.servlet.context-path}")
    String basePath;

    @Value("${info.application.name}")
    private String name;

    @Value("${info.application.version}")
    private String version;

    @Value("${info.properties.environment}")
    private String environment;

    @Autowired
    private ConfigCacheService configCacheService;


    /**
     * @return redirect to Swagger page documentation
     */
    @Hidden
    @GetMapping("")
    public RedirectView home() {
        if (!basePath.endsWith("/")) {
            basePath += "/";
        }
        return new RedirectView(basePath + "swagger-ui.html");
    }

    /**
     * Health Check
     *
     * @return ok
     */
    @Operation(summary = "Return OK if application is started", security = {@SecurityRequirement(name = "ApiKey")}, tags = {"Home"})
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "OK.", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AppInfoResponse.class))),
    })
    @GetMapping("/info")
    public AppInfoResponse healthCheck() {
        return AppInfoResponse.builder()
                .name(name)
                .version(version)
                .environment(environment)
                .cacheVersion(configCacheService.getConfigData().getVersion())
                .build();
    }

}
