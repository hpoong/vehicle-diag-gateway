package com.hopoong.diag.gateway.ops.persistence;

import java.time.OffsetDateTime;

public record EventRow(long id, String eventId, long resultId, String status, int attemptCount, int cycleNo, OffsetDateTime nextAttemptAt, String lockedBy, OffsetDateTime lockedUntil, String lastOutcome, Integer lastHttpStatus, String lastError, String deadReason, OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime sentAt, OffsetDateTime deadAt) {}
