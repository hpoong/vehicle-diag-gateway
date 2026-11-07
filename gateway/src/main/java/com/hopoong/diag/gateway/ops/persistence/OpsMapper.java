package com.hopoong.diag.gateway.ops.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OpsMapper {
    SummaryRow summary();
    List<EventRow> findEvents(@Param("status") String status, @Param("limit") int limit);
    Optional<EventRow> findEvent(@Param("id") long id);
    boolean existsEvent(@Param("id") long id);
    List<AttemptRow> findAttempts(@Param("outboxId") long outboxId);
    Optional<RequeueRow> requeue(@Param("outboxId") long outboxId);
    void insertActionLog(@Param("outboxId") long outboxId, @Param("operator") String operator,
            @Param("reason") String reason, @Param("beforeAttemptCount") int beforeAttemptCount,
            @Param("newCycleNo") int newCycleNo, @Param("requestId") String requestId);
}
