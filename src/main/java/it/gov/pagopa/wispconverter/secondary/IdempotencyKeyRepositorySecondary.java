package it.gov.pagopa.wispconverter.secondary;

import com.azure.spring.data.cosmos.repository.CosmosRepository;
import it.gov.pagopa.wispconverter.repository.model.IdempotencyKeyEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

@Repository
@Qualifier("secondaryCosmosTemplate")
public interface IdempotencyKeyRepositorySecondary extends CosmosRepository<IdempotencyKeyEntity, String> {

}
