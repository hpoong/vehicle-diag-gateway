package com.hopoong.diag.gateway.delivery;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OutboxMapper {
    void insert(@Param("resultId") long resultId, @Param("eventId") String eventId, @Param("payload") String payload);
    Optional<DeliveryRow> findByResultId(@Param("resultId") long resultId);
    java.util.List<ClaimedEvent> claim(@Param("limit") int limit, @Param("instanceId") String instanceId,
                                       @Param("leaseMillis") long leaseMillis);
    int markSent(@Param("id") long id, @Param("claimToken") String claimToken, @Param("httpStatus") Integer httpStatus);
    int reschedule(@Param("id") long id, @Param("claimToken") String claimToken, @Param("delayMillis") long delayMillis,
                   @Param("outcome") String outcome, @Param("httpStatus") Integer httpStatus, @Param("error") String error);
    int markDead(@Param("id") long id, @Param("claimToken") String claimToken, @Param("deadReason") String deadReason,
                 @Param("outcome") String outcome, @Param("httpStatus") Integer httpStatus, @Param("error") String error);
    int markSkippedDead(@Param("id") long id, @Param("claimToken") String claimToken);
    record DeliveryRow(String eventId, String status, int attemptCount, String lastOutcome, OffsetDateTime sentAt) {}
}
