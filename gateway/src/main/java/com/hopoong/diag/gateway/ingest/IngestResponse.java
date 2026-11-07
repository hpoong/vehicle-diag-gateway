package com.hopoong.diag.gateway.ingest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
public record IngestResponse(long resultId, String deviceId, String eventId, String status, String policyCode,
        Instant receivedAt, Delivery delivery) {
    public record Delivery(UUID eventId, String status) {}
    public static IngestResponse from(IngestResult result) {
        return new IngestResponse(result.resultId(), result.deviceId(), result.eventId(), result.outcome().name(),
                result.policyCode(), result.receivedAt().truncatedTo(ChronoUnit.MILLIS),
                new Delivery(result.deliveryEventId(), result.deliveryStatus()));
    }
}
