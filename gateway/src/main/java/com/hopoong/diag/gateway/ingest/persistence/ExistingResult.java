package com.hopoong.diag.gateway.ingest.persistence;

import java.time.OffsetDateTime;

public record ExistingResult(long id, String payloadHash, String policyCode, OffsetDateTime receivedAt,
        String deliveryEventId, String deliveryStatus) {}
