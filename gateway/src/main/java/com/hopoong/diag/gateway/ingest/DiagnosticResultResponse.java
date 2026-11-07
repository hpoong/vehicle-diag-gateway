package com.hopoong.diag.gateway.ingest;
import java.time.Instant;
import java.util.List;
public record DiagnosticResultResponse(long resultId, String deviceId, String eventId, String brand, String vin,
        String sessionId, Instant occurredAt, Instant receivedAt, String policyCode, List<EcuView> ecus,
        DeliveryView delivery) {
    public record EcuView(String ecuId, String swVersion, List<String> dtcCodes) {}
    public record DeliveryView(String eventId, String status, int attemptCount, String lastOutcome, Instant sentAt) {}
}
