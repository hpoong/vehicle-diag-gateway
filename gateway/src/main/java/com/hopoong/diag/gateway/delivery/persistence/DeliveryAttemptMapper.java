package com.hopoong.diag.gateway.delivery.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DeliveryAttemptMapper {
    int abandonStarted(@Param("outboxIds") List<Long> outboxIds);
    void insertStarted(@Param("outboxId") long outboxId, @Param("cycleNo") int cycleNo,
                       @Param("attemptNo") int attemptNo, @Param("claimToken") String claimToken,
                       @Param("workerId") String workerId);
    void insertSkipped(@Param("outboxId") long outboxId, @Param("cycleNo") int cycleNo,
                       @Param("attemptNo") int attemptNo, @Param("claimToken") String claimToken,
                       @Param("workerId") String workerId);
    int recordLateOutcome(@Param("claimToken") String claimToken, @Param("lateOutcome") String lateOutcome);
    int finish(@Param("claimToken") String claimToken, @Param("outcome") String outcome,
               @Param("httpStatus") Integer httpStatus, @Param("errorCode") String errorCode,
               @Param("errorMessage") String errorMessage, @Param("latencyMs") long latencyMs);
}
