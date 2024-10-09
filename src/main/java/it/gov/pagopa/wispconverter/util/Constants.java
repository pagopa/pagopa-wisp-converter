package it.gov.pagopa.wispconverter.util;

import lombok.experimental.UtilityClass;

import java.math.BigDecimal;

@UtilityClass
public class Constants {

    public static final String OK = "OK";
    public static final String KO = "KO";

    public static final String SERVICE_CODE_APP = "WIC";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String PAA_INVIA_RT = "paaInviaRT";
    public static final String SEND_PAYMENT_RESULT_V2 = "sendPaymentResultV2";
    public static final String NODO_INVIA_RPT = "nodoInviaRPT";
    public static final String NODO_INVIA_CARRELLO_RPT = "nodoInviaCarrelloRPT";
    public static final String CLOSE_PAYMENT_V2 = "closePaymentV2";
    public static final String SOAP_ENV = "soapenv";

    public static final String PPT = "ppt";
    public static final String PPT_HEAD = "ppthead";
    public static final BigDecimal AMOUNT_SCALE_INCREMENT = new BigDecimal(100L);

    public static final String MDC_INSERTED_TIMESTAMP = "insertTimestamp";
    public static final String MDC_CONTROL_FLAG = "controlFlag";
    public static final String MDC_START_TIME = "startTime";
    public static final String MDC_CALL_TYPE = "callType";
    public static final String MDC_EVENT_CATEGORY = "eventCategory";
    public static final String MDC_EVENT_SUB_CATEGORY = "eventSubCategory";
    public static final String MDC_STATUS = "status";
    public static final String MDC_STATUS_CODE = "httpCode";
    public static final String MDC_EXECUTION_TIME = "executionTime";
    public static final String MDC_ERROR_CODE = "errorCode";
    public static final String MDC_ERROR_TITLE = "errorTitle";
    public static final String MDC_ERROR_DETAIL = "errorDetail";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_OPERATION_ID = "operationId";
    public static final String MDC_BUSINESS_PROCESS = "businessProcess";
    public static final String MDC_CLIENT_OPERATION_ID = "clientOperationId";
    public static final String MDC_CLIENT_SERVICE_ID = "clientServiceId";
    public static final String MDC_CLIENT_EXECUTION_TIME = "clientExecutionTime";
    public static final String MDC_CLIENT_TYPE = "clientType";
    public static final String MDC_SESSION_ID = "sessionId";
    public static final String MDC_PRIMITIVE = "primitive";
    public static final String MDC_IUV = "iuv";
    public static final String MDC_NOTICE_NUMBER = "noticeNumber";
    public static final String MDC_PAYMENT_TOKEN = "paymentToken";
    public static final String MDC_CCP = "ccp";
    public static final String MDC_CART_ID = "cartId";
    public static final String MDC_DOMAIN_ID = "domainId";
    public static final String MDC_PSP_ID = "pspId";
    public static final String MDC_STATION_ID = "stationId";
    public static final String MDC_CHANNEL_ID = "channelId";
}
