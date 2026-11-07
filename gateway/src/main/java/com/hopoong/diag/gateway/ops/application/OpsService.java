package com.hopoong.diag.gateway.ops.application;

import com.hopoong.diag.gateway.common.error.GatewayErrorCode;
import com.hopoong.diag.gateway.ops.persistence.OpsMapper;
import com.hopoong.diag.gateway.ops.web.*;
import com.hopoong.diag.websupport.error.BusinessException;
import com.hopoong.diag.websupport.error.CommonErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpsService {
    private final OpsMapper mapper;
    public OpsSummaryResponse getSummary() {
        var row = mapper.summary();
        var counts = new LinkedHashMap<String, Long>();
        counts.put("PENDING", row.pending()); counts.put("SENT", row.sent());
        counts.put("DEAD", row.dead()); counts.put("TOTAL", row.total());
        var dead = new LinkedHashMap<String, Long>();
        dead.put("NON_RETRYABLE", row.deadNonRetryable()); dead.put("MAX_ATTEMPTS", row.deadMaxAttempts());
        return new OpsSummaryResponse(row.asOf().toInstant(), counts, row.oldestPendingAgeSeconds(), dead);
    }
    public List<OpsEventView> getEvents(OutboxStatus status, int limit) {
        return mapper.findEvents(status.name(), limit).stream().map(OpsEventView::from).toList();
    }
    public List<OpsAttemptView> getAttempts(long outboxId) {
        if (!mapper.existsEvent(outboxId)) throw new BusinessException(CommonErrorCode.NOT_FOUND);
        return mapper.findAttempts(outboxId).stream().map(OpsAttemptView::from).toList();
    }
    @Transactional(rollbackFor = Exception.class)
    public OpsEventView requeue(long outboxId, String operator, String reason, String requestId) {
        var row = mapper.requeue(outboxId).orElseThrow(() -> new BusinessException(mapper.existsEvent(outboxId)
                ? GatewayErrorCode.INVALID_STATE_TRANSITION : CommonErrorCode.NOT_FOUND));
        mapper.insertActionLog(outboxId, operator, reason, row.beforeAttemptCount(), row.newCycleNo(), requestId);
        log.info("OPS_REQUEUE outboxId={} operator={} beforeAttemptCount={} newCycleNo={}",
                outboxId, operator, row.beforeAttemptCount(), row.newCycleNo());
        return OpsEventView.from(mapper.findEvent(outboxId).orElseThrow());
    }
}
