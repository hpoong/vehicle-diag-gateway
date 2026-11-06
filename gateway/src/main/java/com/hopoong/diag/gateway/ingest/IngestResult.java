package com.hopoong.diag.gateway.ingest;

import java.time.Instant;
import java.util.UUID;

public record IngestResult(Outcome outcome, long resultId, String deviceId, String eventId, String policyCode,
        Instant receivedAt, UUID deliveryEventId, String deliveryStatus) {
    public enum Outcome { ACCEPTED, DUPLICATE }
}
