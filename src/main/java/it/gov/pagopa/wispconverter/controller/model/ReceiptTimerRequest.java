package it.gov.pagopa.wispconverter.controller.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.*;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReceiptTimerRequest {

    private String paymentToken;

    private String sessionId;

    @Pattern(regexp="\\w*")
    private String fiscalCode;

    @Pattern(regexp="\\d*")
    private String noticeNumber;

    @PositiveOrZero
    private Long expirationTime; // milliseconds
}
