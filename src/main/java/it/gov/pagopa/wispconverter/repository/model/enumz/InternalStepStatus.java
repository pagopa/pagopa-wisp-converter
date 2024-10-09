package it.gov.pagopa.wispconverter.repository.model.enumz;

public enum InternalStepStatus {

    FOUND_RPT_IN_STORAGE,
    FOUND_RT_IN_STORAGE,
    EXTRACTED_DATA_FROM_RPT,
    CREATED_NEW_PAYMENT_POSITION_IN_GPD,
    GENERATED_NAV_FOR_NEW_PAYMENT_POSITION,
    UPDATED_EXISTING_PAYMENT_POSITION_IN_GPD,
    GENERATING_RT_FOR_INVALID_PAYMENT_POSITION_IN_GPD,
    GENERATING_RT_FOR_REDIRECT_ERROR,
    GENERATED_CACHE_ABOUT_RPT_FOR_DECOUPLER,
    GENERATED_CACHE_ABOUT_RPT_FOR_CARTSESSION_CACHING,
    GENERATED_CACHE_ABOUT_RPT_FOR_RT_GENERATION,
    SAVED_RPT_IN_CART_RECEIVED_REDIRECT_URL_FROM_CHECKOUT,
    RT_NOT_GENERABLE_FOR_GPD_STATION,
    RT_NOT_GENERABLE_FOR_NOT_EXISTING_PAYMENT_POSITION,
    NEGATIVE_RT_TRY_TO_SEND_TO_CREDITOR_INSTITUTION,
    POSITIVE_RT_TRY_TO_SEND_TO_CREDITOR_INSTITUTION,
    RT_SEND_SUCCESS,
    RT_SEND_FAILURE,
    RT_ALREADY_SENT,
    RT_SEND_SCHEDULING_SUCCESS,
    RT_SEND_SCHEDULING_FAILURE,
    RT_SCHEDULED_SEND_SUCCESS,
    RT_SCHEDULED_SEND_FAILURE,
    RT_SEND_RESCHEDULING_FAILURE,
    RT_SEND_RESCHEDULING_REACHED_MAX_RETRIES,
    RT_SEND_RESCHEDULING_SUCCESS,
    RT_START_RECONCILIATION_PROCESS,
    RT_END_RECONCILIATION_PROCESS,
    RECEIPT_TIMER_GENERATION_CREATED_SCHEDULED_SEND,
    RECEIPT_TIMER_GENERATION_CACHED_SEQUENCE_NUMBER,
    RECEIPT_TIMER_GENERATION_DELETED_SCHEDULED_SEND,
    RECEIPT_TIMER_GENERATION_SKIP_DELETE_SCHEDULED_SEND,
    RECEIPT_TIMER_PAYMENT_TOKEN_TIMEOUT_TRIGGER,
    ECOMMERCE_HANG_TIMER_TRIGGER,
    ECOMMERCE_HANG_TIMER_CREATED,
    ECOMMERCE_HANG_TIMER_DELETED,
    RPT_TIMER_TRIGGER,
    RPT_TIMER_CREATED,
    RPT_TIMER_DELETED,
    RPT_TIMER_NOT_SET,
    COMMUNICATING_WITH_GPD_REQUEST,
    COMMUNICATING_WITH_GPD_RESPONSE,
    COMMUNICATING_WITH_IUV_GENERATOR_REQUEST,
    COMMUNICATING_WITH_IUV_GENERATOR_RESPONSE,
    COMMUNICATING_WITH_CHECKOUT_REQUEST,
    COMMUNICATING_WITH_CHECKOUT_RESPONSE,
    COMMUNICATING_WITH_DECOUPLER_CACHING_REQUEST,
    COMMUNICATING_WITH_DECOUPLER_CACHING_RESPONSE,
    COMMUNICATING_WITH_CREDITOR_INSTITUTION_REQUEST,
    COMMUNICATING_WITH_CREDITOR_INSTITUTION_RESPONSE,
    ;

    public static InternalStepStatus getStatusFromClientCommunication(ClientEnum client, EventSubcategoryEnum point) {
        InternalStepStatus status = null;
        switch (client) {
            case GPD -> {
                if (EventSubcategoryEnum.REQ.equals(point)) {
                    status = COMMUNICATING_WITH_GPD_REQUEST;
                } else if (EventSubcategoryEnum.RESP.equals(point)) {
                    status = COMMUNICATING_WITH_GPD_RESPONSE;
                }
            }
            case CHECKOUT -> {
                if (EventSubcategoryEnum.REQ.equals(point)) {
                    status = COMMUNICATING_WITH_CHECKOUT_REQUEST;
                } else if (EventSubcategoryEnum.RESP.equals(point)) {
                    status = COMMUNICATING_WITH_CHECKOUT_RESPONSE;
                }
            }
            case IUV_GENERATOR -> {
                if (EventSubcategoryEnum.REQ.equals(point)) {
                    status = COMMUNICATING_WITH_IUV_GENERATOR_REQUEST;
                } else if (EventSubcategoryEnum.RESP.equals(point)) {
                    status = COMMUNICATING_WITH_IUV_GENERATOR_RESPONSE;
                }
            }
            case DECOUPLER_CACHING -> {
                if (EventSubcategoryEnum.REQ.equals(point)) {
                    status = COMMUNICATING_WITH_DECOUPLER_CACHING_REQUEST;
                } else if (EventSubcategoryEnum.RESP.equals(point)) {
                    status = COMMUNICATING_WITH_DECOUPLER_CACHING_RESPONSE;
                }
            }
            case CREDITOR_INSTITUTION_ENDPOINT -> {
                if (EventSubcategoryEnum.REQ.equals(point)) {
                    status = COMMUNICATING_WITH_CREDITOR_INSTITUTION_REQUEST;
                } else if (EventSubcategoryEnum.RESP.equals(point)) {
                    status = COMMUNICATING_WITH_CREDITOR_INSTITUTION_RESPONSE;
                }
            }
        }
        return status;
    }
}
