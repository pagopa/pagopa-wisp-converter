package it.gov.pagopa.wispconverter.repository.model.enumz;

public enum ReceiptStatusEnum {
    REDIRECT,
    // PAYING status cover the time between DELETE receipt/timer and (POST receipt/ok or receipt/ko) calls
    PAYING,
    SENDING,
    SCHEDULED,
    SENT,
    NOT_SENT,
    SENT_REJECTED_BY_EC
}