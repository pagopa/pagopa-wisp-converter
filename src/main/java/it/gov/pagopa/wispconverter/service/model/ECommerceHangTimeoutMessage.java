package it.gov.pagopa.wispconverter.service.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.gson.Gson;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ECommerceHangTimeoutMessage {

    private String fiscalCode;
    private String noticeNumber;
    private String sessionId;

    @Override
    public String toString() {
       return new Gson().toJson(this);
    }
}
