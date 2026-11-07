package com.hopoong.diag.gateway.ingest.application;
import java.time.Instant;
import java.util.UUID;
public record SavedResult(long resultId, Instant receivedAt, UUID deliveryEventId) {}
