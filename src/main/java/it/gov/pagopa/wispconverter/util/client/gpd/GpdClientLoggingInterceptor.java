package it.gov.pagopa.wispconverter.util.client.gpd;

import it.gov.pagopa.wispconverter.service.ReService;
import it.gov.pagopa.wispconverter.util.client.AbstractAppClientLoggingInterceptor;
import it.gov.pagopa.wispconverter.util.client.ClientLoggingProperties;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;

@Slf4j
public class GpdClientLoggingInterceptor extends AbstractAppClientLoggingInterceptor {

  public GpdClientLoggingInterceptor(ClientLoggingProperties clientLoggingProperties, ReService reService){
    super(clientLoggingProperties, reService);
  }

  @Override
  protected void request(String clientOperationId, String operationId, HttpRequest request, byte[] reqBody) {
    if(log.isDebugEnabled()){
      log.debug(createRequestMessage(clientOperationId, operationId, request, reqBody));
    }
  }

  @SneakyThrows
  @Override
  protected void response(String clientOperationId, String operationId, String clientExecutionTime, HttpRequest request, ClientHttpResponse response) {
    if(log.isDebugEnabled()){
      log.debug(createResponseMessage(clientOperationId, operationId, clientExecutionTime, request, response));
    }
  }
}
