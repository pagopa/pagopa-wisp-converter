package it.gov.pagopa.wispconverter.repository.secondary;


import com.azure.spring.data.cosmos.repository.CosmosRepository;
import com.azure.spring.data.cosmos.repository.Query;
import it.gov.pagopa.wispconverter.repository.model.ReEventEntity;
import it.gov.pagopa.wispconverter.repository.model.SessionIdEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Qualifier("secondaryCosmosTemplate")
public interface ReEventRepositorySecondary extends CosmosRepository<ReEventEntity, String> {

    @Query("SELECT * FROM c " +
            "WHERE (c.partitionKey >= @dateFrom AND c.partitionKey <= @dateTo) " +
            "AND c.iuv = @iuv " +
            "AND c.domainId = @organizationId")
    List<ReEventEntity> findByIuvAndOrganizationId(@Param("dateFrom") String dateFrom,
                                                   @Param("dateTo") String dateTo,
                                                   @Param("iuv") String iuv,
                                                   @Param("organizationId") String organizationId);


    @Query("SELECT * FROM c " +
            "WHERE (c.partitionKey >= @dateFrom AND c.partitionKey <= @dateTo) " +
            "AND c.sessionId = @sessionId " +
            "AND c.status = @status ORDER BY c._ts OFFSET 0 LIMIT @limit")
    List<ReEventEntity> findBySessionIdAndStatus(@Param("dateFrom") String dateFrom,
                                                 @Param("dateTo") String dateTo,
                                                 @Param("sessionId") String sessionId,
                                                 @Param("status") String status,
                                                 @Param("limit") int limit);

    @Query("SELECT * FROM c " +
            "WHERE c.partitionKey = @date " +
            "AND c.sessionId = @sessionId " +
            "AND c.status = @status " +
            "AND c.businessProcess = @businessProcess " +
            "ORDER BY c._ts")
    List<ReEventEntity> findBySessionIdAndStatusAndPartitionKey(@Param("date") String partitionKey,
                                                                @Param("sessionId") String sessionId,
                                                                @Param("status") String status,
                                                                @Param("businessProcess") String businessProcess);

    @Query("SELECT * FROM c " +
            "WHERE c.sessionId = @sessionId " +
            "AND c.status = 'SEMANTIC_CHECK_PASSED' ORDER BY c._ts")
    List<ReEventEntity> findRptAccettataNodoBySessionId(@Param("sessionId") String sessionId);

    @Query("SELECT wispSession.sessionId " +
            "FROM ( " +
            "SELECT c.sessionId, COUNT(1) AS occurrences " +
            "FROM c " +
            "WHERE c._ts * 1000 >= DateTimeToTimestamp(@dateFrom) " +
            "AND c._ts * 1000 < DateTimeToTimestamp(@dateTo) " +
            "AND ( " +
            "(c.status = 'TRIGGER_PRIMITIVE_PROCESSED' AND c.businessProcess != 'nodoChiediCopiaRT') " +
            "OR " +
            "(c.businessProcess = 'redirect' AND c.status = 'RPTS_EXTRACTED')" +
            " ) " +
            "GROUP BY c.sessionId " +
            ") AS wispSession " +
            "WHERE wispSession.occurrences = 1"
    )
    List<SessionIdEntity> findSessionWithoutRedirect(@Param("dateFrom") String dateFrom, @Param("dateTo") String dateTo);
}
