package it.gov.pagopa.wispconverter.util;

import gov.telematici.pagamenti.ws.nodoperpa.ppthead.IntestazionePPT;
import it.gov.pagopa.wispconverter.service.model.session.CommonFieldsDTO;
import it.gov.pagopa.wispconverter.service.model.session.RPTContentDTO;
import it.gov.pagopa.wispconverter.service.model.session.SessionDataDTO;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MDCUtil {

    private static final String FAILED = "Failed";
    private static final String SUCCESS = "Success";

    public static void setSessionDataInfo(SessionDataDTO sessionData, String primitive) {

        String controlFlag = MDC.get(Constants.MDC_CONTROL_FLAG);
        if (!"TRUE".equals(controlFlag)) {

            CommonFieldsDTO commonFields = sessionData.getCommonFields();
            MDC.put(Constants.MDC_CONTROL_FLAG, "TRUE");

            MDC.put(Constants.MDC_CART_ID, commonFields.getCartId());
            MDC.put(Constants.MDC_STATION_ID, commonFields.getStationId());
            MDC.put(Constants.MDC_CHANNEL_ID, commonFields.getChannelId());
            MDC.put(Constants.MDC_PSP_ID, commonFields.getPspId());

            if (primitive != null) {
                MDC.put(Constants.MDC_PRIMITIVE, primitive);

                // if the primitive is nodoInviaCarrelloRPT, it means that a cart was extracted, so set
                // cartId in MDC. Otherwise, set IUV and CCP in MDC
                if (Constants.NODO_INVIA_CARRELLO_RPT.equals(primitive)) {
                    MDC.put(Constants.MDC_CART_ID, commonFields.getCartId());
                } else {
                    RPTContentDTO singleRpt = sessionData.getFirstRPT();
                    MDC.put(Constants.MDC_IUV, singleRpt.getIuv());
                    MDC.put(Constants.MDC_CCP, singleRpt.getCcp());
                }
            }
        }
    }

    public static void setSessionDataInfo(IntestazionePPT header, String noticeNumber) {
        MDC.put(Constants.MDC_DOMAIN_ID, header.getIdentificativoDominio());
        MDC.put(Constants.MDC_STATION_ID, header.getIdentificativoStazioneIntermediarioPA());
        MDC.put(Constants.MDC_IUV, header.getIdentificativoUnivocoVersamento());
        MDC.put(Constants.MDC_NOTICE_NUMBER, noticeNumber);
        MDC.put(Constants.MDC_CCP, header.getCodiceContestoPagamento());
    }

    public static void setMDCError(ProblemDetail problemDetail) {
        MDC.put(Constants.MDC_ERROR_DETAIL, problemDetail.getDetail());
        Map<String, Object> properties = problemDetail.getProperties();
        if (properties != null) {
            String errorCode = (String) properties.get(ErrorUtil.EXTRA_FIELD_ERROR_CODE);
            MDC.put(Constants.MDC_ERROR_CODE, errorCode);
        }
    }

    public static void setSessionDataInfo(String businessProcess) {
        String operationId = UUID.randomUUID().toString();
        MDC.put(Constants.MDC_START_TIME, String.valueOf(System.currentTimeMillis()));
        MDC.put(Constants.MDC_OPERATION_ID, operationId);
        MDC.put(Constants.MDC_BUSINESS_PROCESS, businessProcess);
    }

    public static void setReceiptTimerInfoInMDC(
            String domainId, String noticeNumber, String paymentToken) {

        MDC.put(Constants.MDC_DOMAIN_ID, domainId);
        MDC.put(Constants.MDC_PAYMENT_TOKEN, paymentToken);
        MDC.put(Constants.MDC_NOTICE_NUMBER, noticeNumber);
    }

    public static void setEcommerceHangTimerInfoInMDC(String domainId, String noticeNumber) {
        MDC.put(Constants.MDC_DOMAIN_ID, domainId);
        MDC.put(Constants.MDC_NOTICE_NUMBER, noticeNumber);
    }

    public static void removeEcommerceHangTimerInfoInMDC() {
        MDC.remove(Constants.MDC_DOMAIN_ID);
        MDC.remove(Constants.MDC_NOTICE_NUMBER);
    }

    public static void setRPTTimerInfoInMDC(String sessionId) {
        MDC.put(Constants.MDC_SESSION_ID, sessionId);
    }

    public static void setMDCCloseSuccessOperation(int statusCode) {
        setMDCCloseOperation(SUCCESS, statusCode);
    }

    public static void setMDCCloseFailedOperation(int statusCode) {
        setMDCCloseOperation(FAILED, statusCode);
    }

    private static void setMDCCloseOperation(String status, int statusCode) {
        MDC.put(Constants.MDC_STATUS, status);
        MDC.put(Constants.MDC_STATUS_CODE, String.valueOf(statusCode));
        String executionTime = CommonUtility.getExecutionTime(MDC.get(Constants.MDC_START_TIME));
        MDC.put(Constants.MDC_EXECUTION_TIME, executionTime);
    }

    public static boolean hasStatus() {
        return MDC.get(Constants.MDC_STATUS) != null;
    }


    public static Runnable withMdc(Runnable runnable) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> {
            MDC.setContextMap(mdc);
            runnable.run();
        };
    }

    public static <U> Supplier<U> withMdc(Supplier<U> supplier) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> {
            MDC.setContextMap(mdc);
            return supplier.get();
        };
    }
}
