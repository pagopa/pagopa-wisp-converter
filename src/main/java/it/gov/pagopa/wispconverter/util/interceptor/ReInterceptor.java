package it.gov.pagopa.wispconverter.util.interceptor;

import it.gov.pagopa.wispconverter.repository.model.enumz.CallTypeEnum;
import it.gov.pagopa.wispconverter.repository.model.enumz.EventCategoryEnum;
import it.gov.pagopa.wispconverter.repository.model.enumz.EventSubcategoryEnum;
import it.gov.pagopa.wispconverter.service.ReService;
import it.gov.pagopa.wispconverter.service.model.re.ReEventDto;
import it.gov.pagopa.wispconverter.util.ReUtil;
import it.gov.pagopa.wispconverter.util.Trace;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import static it.gov.pagopa.wispconverter.util.Constants.*;

@Slf4j
@RequiredArgsConstructor
public class ReInterceptor implements HandlerInterceptor {

    private final ReService reService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (handler instanceof HandlerMethod handlerMethod) {
            Trace trace = handlerMethod.getMethod().getAnnotation(Trace.class);
            if (trace != null) {
                if (trace.reEnabled()) {
                    String businessProcess = trace.businessProcess();
                    log.debug("[preHandle] trace RE SERVER IN businessProcess = [{}]", businessProcess);
                    MDC.put(MDC_CALL_TYPE, CallTypeEnum.SERVER.name());
                    MDC.put(MDC_EVENT_CATEGORY, EventCategoryEnum.INTERFACE.name());
                    MDC.put(MDC_EVENT_SUB_CATEGORY, EventSubcategoryEnum.REQ.name());
                    ReEventDto reEventDtoServerIN = ReUtil.createREForServerInterfaceInRequestEvent(request);
                    reService.addRe(reEventDtoServerIN);
                }
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        if (handler instanceof HandlerMethod handlerMethod) {
            Trace trace = handlerMethod.getMethod().getAnnotation(Trace.class);
            if (trace != null) {
                if (trace.reEnabled()) {
                    String businessProcess = trace.businessProcess();
                    log.debug("[afterCompletion] trace RE SERVER OUT businessProcess = [{}]", businessProcess);
                    MDC.put(MDC_CALL_TYPE, CallTypeEnum.SERVER.name());
                    MDC.put(MDC_EVENT_CATEGORY, EventCategoryEnum.INTERFACE.name());
                    MDC.put(MDC_EVENT_SUB_CATEGORY, EventSubcategoryEnum.RESP.name());
                    ReEventDto reEventDtoServerOUT = ReUtil.createREForServerInterfaceInResponseEvent(request, response);
                    reService.addRe(reEventDtoServerOUT);
                }
            }
        }
    }


}
