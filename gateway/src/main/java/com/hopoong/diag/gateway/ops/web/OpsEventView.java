package com.hopoong.diag.gateway.ops.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import com.hopoong.diag.gateway.ops.persistence.EventRow;

public record OpsEventView(long outboxId, String eventId, Long resultId, String eventType, Long campaignId, String status, int attemptCount, int cycleNo, Instant nextAttemptAt, String lockedBy, Instant lockedUntil, String lastOutcome, Integer lastHttpStatus, String lastError, String deadReason, Instant createdAt, Instant updatedAt, Instant sentAt, Instant deadAt) {
    private static Instant instant(OffsetDateTime time) { return time == null ? null : time.toInstant(); }
    public static OpsEventView from(EventRow r) {
        return new OpsEventView(r.id(), r.eventId(), r.resultId(), r.eventType(), r.campaignId(), r.status(), r.attemptCount(), r.cycleNo(), instant(r.nextAttemptAt()), r.lockedBy(), instant(r.lockedUntil()), r.lastOutcome(), r.lastHttpStatus(), r.lastError(), r.deadReason(), instant(r.createdAt()), instant(r.updatedAt()), instant(r.sentAt()), instant(r.deadAt()));
    }
}
