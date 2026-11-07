package com.hopoong.diag.gateway.ops.persistence;

public record RequeueRow(int newCycleNo, int beforeAttemptCount) {}
