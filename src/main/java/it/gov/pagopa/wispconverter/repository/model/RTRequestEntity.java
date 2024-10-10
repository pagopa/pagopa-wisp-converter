package it.gov.pagopa.wispconverter.repository.model;

import com.azure.spring.data.cosmos.core.mapping.Container;
import com.azure.spring.data.cosmos.core.mapping.PartitionKey;
import it.gov.pagopa.wispconverter.repository.model.enumz.ReceiptTypeEnum;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.data.annotation.Id;

import java.util.List;

@Container(containerName = "receipt")
@Data
@ToString(exclude = "payload")
@EqualsAndHashCode(exclude = "payload")
@Builder(toBuilder = true)
public class RTRequestEntity {

    @Id
    private String id;

    @PartitionKey
    private String partitionKey;

    private String domainId;

    private String iuv;

    private String ccp;

    private String sessionId;

    private String primitive;

    private String payload;

    private ReceiptTypeEnum receiptType;

    private String url;

    private String proxyAddress;

    private List<String> headers;

    private Integer retry;

    private String idempotencyKey;

    private String station;
}
