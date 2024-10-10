package it.gov.pagopa.wispconverter.controller.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import org.springframework.data.util.Pair;

import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecoveryReceiptReportResponse {

    private List<Pair<String, String>> receiptStatus;
}
