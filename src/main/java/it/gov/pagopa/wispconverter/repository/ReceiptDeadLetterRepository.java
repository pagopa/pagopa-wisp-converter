package it.gov.pagopa.wispconverter.repository;

import com.azure.spring.data.cosmos.repository.CosmosRepository;
import it.gov.pagopa.wispconverter.repository.model.ReceiptDeadLetterEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface ReceiptDeadLetterRepository extends CosmosRepository<ReceiptDeadLetterEntity, String> {

}
