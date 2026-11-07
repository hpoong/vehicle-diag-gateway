package com.hopoong.diag.gateway.ops.persistence;

public record AttemptRow(int cycleNo, int attemptNo, String workerId, String outcome, Integer httpStatus, String errorCode, Integer latencyMs) {}
