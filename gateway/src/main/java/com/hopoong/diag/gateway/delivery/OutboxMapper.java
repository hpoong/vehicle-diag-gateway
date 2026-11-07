package com.hopoong.diag.gateway.delivery;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OutboxMapper {
    void insert(@Param("resultId") long resultId, @Param("eventId") String eventId, @Param("payload") String payload);
    Optional<DeliveryRow> findByResultId(@Param("resultId") long resultId);
    record DeliveryRow(String eventId, String status, int attemptCount, String lastOutcome, OffsetDateTime sentAt) {}
}
