package it.gov.pagopa.wispconverter.config;

import static it.gov.pagopa.wispconverter.util.CommonUtility.deNull;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.PostConstruct;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.xml.bind.JAXBElement;
import lombok.extern.slf4j.Slf4j;


@Aspect
@Component
@Slf4j
public class LoggingAspect {

  public static final String START_TIME = "startTime";
  public static final String METHOD = "method";
  public static final String STATUS = "status";
  public static final String CODE = "httpCode";
  public static final String RESPONSE_TIME = "responseTime";
  public static final String FAULT_CODE = "faultCode";
  public static final String FAULT_DETAIL = "faultDetail";
  public static final String REQUEST_ID = "requestId";
  public static final String OPERATION_ID = "operationId";
  public static final String ARGS = "args";

  final HttpServletRequest httRequest;

  final HttpServletResponse httpResponse;

  @Value("${info.application.name}")
  private String name;

  @Value("${info.application.version}")
  private String version;

  @Value("${info.properties.environment}")
  private String environment;

  public LoggingAspect(HttpServletRequest httRequest, HttpServletResponse httpResponse) {
    this.httRequest = httRequest;
    this.httpResponse = httpResponse;
  }

  private static String getDetail(ResponseEntity<Object> result) {
    if (result != null && result.getBody() != null && result.getBody() instanceof ProblemDetail problemDetail
    		&& problemDetail.getDetail() != null) {
      return problemDetail.getDetail();
    } else return AppErrorCodeMessageEnum.UNKNOWN.getDetail();
  }

  private static String getTitle(ResponseEntity<Object> result) {
    if (result != null && result.getBody() != null && result.getBody() instanceof ProblemDetail problemDetail 
    		&& problemDetail.getTitle() != null) {
      return problemDetail.getTitle();
    } else return AppErrorCodeMessageEnum.UNKNOWN.getTitle();
  }

  public static String getExecutionTime() {
    String startTime = MDC.get(START_TIME);
    if (startTime != null) {
      long endTime = System.currentTimeMillis();
      long executionTime = endTime - Long.parseLong(startTime);
      return String.valueOf(executionTime);
    }
    return "-";
  }

  private static Map<String, String> getParams(ProceedingJoinPoint joinPoint) {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    Method method = signature.getMethod();
    Map<String, String> params = new HashMap<>();
    int i = 0;
    for (var parameter : method.getParameters()) {
      var paramName = parameter.getName();
      var arg = joinPoint.getArgs()[i++];
      if (arg instanceof JAXBElement<?>) {
          try {
              arg = new ObjectMapper().writer().writeValueAsString(arg);
          } catch (JsonProcessingException e) {
              arg = "unreadable!";
          }
      }
      params.put(paramName, deNull(arg));
    }
    return params;
  }

  @Pointcut("@within(org.springframework.web.bind.annotation.RestController)")
  public void restController() {
    // all rest controllers
  }

  @Pointcut("@within(org.springframework.stereotype.Repository)")
  public void repository() {
    // all repository methods
  }

  @Pointcut("@within(org.springframework.stereotype.Service)")
  public void service() {
    // all service methods
  }

  /** Log essential info of application during the startup. */
  @PostConstruct
  public void logStartup() {
    log.info("-> Starting {} version {} - environment {}", name, version, environment);
  }

  @Around(value = "restController()")
  public Object logApiInvocation(ProceedingJoinPoint joinPoint) throws Throwable {
    MDC.put(METHOD, joinPoint.getSignature().getName());
    MDC.put(START_TIME, String.valueOf(System.currentTimeMillis()));
    MDC.put(OPERATION_ID, UUID.randomUUID().toString());
    if (MDC.get(REQUEST_ID) == null) {
      var requestId = UUID.randomUUID().toString();
      MDC.put(REQUEST_ID, requestId);
    }
    Map<String, String> params = getParams(joinPoint);
    MDC.put(ARGS, params.toString());

    log.info("Invoking API operation {} - args: {}", joinPoint.getSignature().getName(), params);

    Object result = joinPoint.proceed();

    MDC.put(STATUS, "OK");
    MDC.put(CODE, String.valueOf(httpResponse.getStatus()));
    MDC.put(RESPONSE_TIME, getExecutionTime());
    log.info("Successful API operation {} - result: {}", joinPoint.getSignature().getName(), result);
    MDC.remove(STATUS);
    MDC.remove(CODE);
    MDC.remove(RESPONSE_TIME);
    MDC.remove(START_TIME);
    return result;
  }
  
  @AfterReturning(value = "execution(* *..advice.GlobalExceptionHandler.handleAppException(..)) || execution(* *..advice.GlobalExceptionHandler.handleGenericException(..))", returning = "result")
  public void trowingApiInvocation(JoinPoint joinPoint, ErrorResponse result) {
    MDC.put(STATUS, "KO");
    MDC.put(CODE, String.valueOf(result.getStatusCode().value()));
    MDC.put(RESPONSE_TIME, getExecutionTime());
    MDC.put(FAULT_CODE, result.getTitleMessageCode());
    MDC.put(FAULT_DETAIL, result.getDetailMessageCode());
    log.info("Failed API operation {} - error: {}", MDC.get(METHOD), result);
    MDC.clear();
  }
  
  @AfterReturning(value = "execution(* *..advice.GlobalExceptionHandler.handleExceptionInternal(..))", returning = "result")
  public void trowingApiInvocation(JoinPoint joinPoint, ResponseEntity<Object> result) {
    MDC.put(STATUS, "KO");
    MDC.put(CODE, String.valueOf(result.getStatusCode().value()));
    MDC.put(RESPONSE_TIME, getExecutionTime());
    MDC.put(FAULT_CODE, getTitle(result));
    MDC.put(FAULT_DETAIL, getDetail(result));
    log.info("Failed API operation {} - error: {}", MDC.get(METHOD), result);
    MDC.clear();
  }

  @Around(value = "repository() || service()")
  public Object logTrace(ProceedingJoinPoint joinPoint) throws Throwable {
    Map<String, String> params = getParams(joinPoint);
    log.debug("Call method {} - args: {}", joinPoint.getSignature().toShortString(), params);
    Object result = joinPoint.proceed();
    log.debug("Return method {} - result: {}", joinPoint.getSignature().toShortString(), result);
    return result;
  }
}
