package com.hopoong.diag.gateway.ingest.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.hopoong.diag.gateway.ingest.domain.DiagnosticResultCommand;

@Mapper
public interface DiagnosticResultMapper {
    Optional<ExistingResult> findByKey(@Param("deviceId") String deviceId, @Param("eventId") String eventId);
    Optional<InsertedResult> insertResult(@Param("cmd") DiagnosticResultCommand cmd,
            @Param("payloadHash") String payloadHash, @Param("policyCode") String policyCode,
            @Param("rawPayload") String rawPayload);
    void insertObservations(@Param("rows") java.util.List<ObservationRow> rows);
    void upsertLatest(@Param("vin") String vin, @Param("ecuId") String ecuId, @Param("resultId") long resultId,
            @Param("occurredAt") java.time.OffsetDateTime occurredAt, @Param("dtcCodes") String[] dtcCodes);
    Optional<ResultRow> findById(@Param("id") long id);
    java.util.List<ObservationRow> findObservations(@Param("resultId") long resultId);
    record ResultRow(long id, String deviceId, String eventId, String brand, String vin, String sessionId,
            java.time.OffsetDateTime occurredAt, java.time.OffsetDateTime receivedAt, String policyCode) {}
}
