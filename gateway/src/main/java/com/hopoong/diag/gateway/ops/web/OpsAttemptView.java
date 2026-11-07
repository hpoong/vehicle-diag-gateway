package com.hopoong.diag.gateway.ops.web;

import com.hopoong.diag.gateway.ops.persistence.AttemptRow;

public record OpsAttemptView(int cycleNo, int attemptNo, String workerId, String outcome, Integer httpStatus, String errorCode, Integer latencyMs) {
    public static OpsAttemptView from(AttemptRow r) { return new OpsAttemptView(r.cycleNo(), r.attemptNo(), r.workerId(), r.outcome(), r.httpStatus(), r.errorCode(), r.latencyMs()); }
}
