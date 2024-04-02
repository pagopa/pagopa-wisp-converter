package it.gov.pagopa.wispconverter.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
public enum AppErrorCodeMessageEnum {
    ERROR(500, "System error", "{0}", HttpStatus.INTERNAL_SERVER_ERROR),
    // --- Internal logic errors ---
    GENERIC_ERROR(1000, "Generic flow error", "Error while executing conversion flow. {0}", HttpStatus.UNPROCESSABLE_ENTITY),
    PARSING_GENERIC_ERROR(1001, "Generic parsing error", "Error while parsing payload. {0}", HttpStatus.BAD_REQUEST),
    PARSING_INVALID_HEADER(1002, "SOAP Header parsing error", "Error while parsing payload. The SOAP header in payload is invalid: {0}", HttpStatus.BAD_REQUEST),
    PARSING_INVALID_BODY(1003, "SOAP Body parsing error", "Error while parsing payload. The SOAP body in payload is invalid: {0}", HttpStatus.BAD_REQUEST),
    PARSING_INVALID_XML_NODES(1004, "XML parsing error", "Error while parsing payload. The list of nodes extracted from document must be greater than zero, but currently it is zero.", HttpStatus.BAD_REQUEST),
    PARSING_INVALID_ZIPPED_PAYLOAD(1005, "ZIP extraction error", "Error while parsing payload. Cannot unzip payload correctly.", HttpStatus.BAD_REQUEST),
    PARSING_PRIMITIVE_NOT_VALID(1006, "Primitive not valid", "Error while checking primitive. Primitive [{0}] not valid.", HttpStatus.NOT_ACCEPTABLE),
    VALIDATION_INVALID_IBANS(1100, "IBANs not valid", "Error while generating debt position for GPD service. The IBAN field must be set if digital stamp is not defined for the transfer.", HttpStatus.BAD_REQUEST),
    CONFIGURATION_INVALID_STATION(1200, "Station not valid", "Error while generating cart for Checkout service. No valid station found with code [{0}].", HttpStatus.NOT_FOUND),
    // --- DB and storage interaction errors ---
    PERSISTENCE_RPT_NOT_FOUND(2000, "RPT not found", "Error while retrieving RPT. RPT with sessionId [{0}] not found.", HttpStatus.NOT_FOUND),
    PERSISTENCE_REQUESTID_CACHING_ERROR(2001, "RequestID caching error", "Error while caching RequestID. {0}", HttpStatus.UNPROCESSABLE_ENTITY),
    // --- Client errors ---
    CLIENT_GPD(3000, "GPD client error", "Error while communicating with GPD service. Status [{0}] - {1}", HttpStatus.EXPECTATION_FAILED),
    CLIENT_IUVGENERATOR_INVALID_RESPONSE(3001, "IUV Generator client error", "Error while communicating with IUV Generator service. Status [{0}] - {1}", HttpStatus.EXPECTATION_FAILED),
    CLIENT_DECOUPLER_CACHING(3002, "Decoupler caching client error", "Error while communicating with decoupler caching API. Status [{0}] - {1}", HttpStatus.EXPECTATION_FAILED),
    CLIENT_CHECKOUT(3003, "Checkout error", "Error while communicating with Checkout service. status [{0}] - {1}", HttpStatus.EXPECTATION_FAILED),
    CLIENT_CHECKOUT_NO_REDIRECT_LOCATION(3004, "Checkout redirect error", "Error while communicating with Checkout service. No valid 'Location' header was found,", HttpStatus.EXPECTATION_FAILED),
    CLIENT_CHECKOUT_INVALID_REDIRECT_LOCATION(3005, "Checkout redirect error", "Error while communicating with Checkout service. An empty 'Location' header was found.", HttpStatus.EXPECTATION_FAILED),

    ;

    private final Integer code;
    private final String title;
    private final String detail;
    private final HttpStatusCode status;

    AppErrorCodeMessageEnum(Integer code, String title, String detail, HttpStatus status) {
        this.code = code;
        this.title = title;
        this.detail = detail;
        this.status = status;
    }
}