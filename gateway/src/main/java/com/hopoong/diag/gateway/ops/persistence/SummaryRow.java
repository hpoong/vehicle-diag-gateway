package com.hopoong.diag.gateway.ops.persistence;

import java.time.OffsetDateTime;

public record SummaryRow(OffsetDateTime asOf, long pending, long sent, long dead, long total,
        Long oldestPendingAgeSeconds, long deadNonRetryable, long deadMaxAttempts) {}
