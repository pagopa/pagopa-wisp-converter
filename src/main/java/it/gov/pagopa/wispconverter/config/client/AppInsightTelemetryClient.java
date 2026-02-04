package it.gov.pagopa.wispconverter.config.client;

import com.microsoft.applicationinsights.TelemetryClient;
import com.microsoft.applicationinsights.TelemetryConfiguration;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Azure Application Insight Telemetry client */
@Service
@Slf4j
public class AppInsightTelemetryClient {

  private final TelemetryClient telemetryClient;

  public AppInsightTelemetryClient(@Value("${azure.application-insights.connection-string}") String connectionString) {
    TelemetryConfiguration aDefault = TelemetryConfiguration.createDefault();
    aDefault.setConnectionString(connectionString);
    this.telemetryClient = new TelemetryClient(aDefault);
  }

  /**
   * Create a custom event on Application Insight with the provided information
   *
   * @param errorCode the application error code
   * @param details details of the custom event
   * @param e exception added to the custom event
   */
  public void createCustomEventForAlert(AppErrorCodeMessageEnum errorCode, String details, Exception e) {
    log.info("Sending custom event for alert: {} - {}", errorCode.name(), details, e);
    String errorMessage = null;
      if (e != null) {
          errorMessage = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
      }
      Map<String, String> props =
        Map.of(
            "type",
            errorCode.getTitle(),
            "title",
            errorCode.getDetail(),
            "details",
            details,
            "cause",
            e != null ? errorMessage: "N/A");
    this.telemetryClient.trackEvent("WISP-CONVERTER", props, null);
  }
}
