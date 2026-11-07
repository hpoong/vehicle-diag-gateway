package com.hopoong.diag.gateway.ingest.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hopoong.diag.gateway.common.UtcTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.hopoong.diag.gateway.ingest.domain.DiagnosticResultCommand;

@Component
public class OutboxPayloadFactory {
    private final ObjectMapper mapper;
    public OutboxPayloadFactory(ObjectMapper mapper) { this.mapper = mapper; }

    public OutboxDraft create(DiagnosticResultCommand cmd, long resultId, Instant receivedAt, String policyCode) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("source", Map.of("system", "diag-gateway", "resultId", resultId,
                "deviceId", cmd.deviceId(), "deviceEventId", cmd.eventId(), "policyCode", policyCode));
        payload.put("vin", cmd.vin());
        payload.put("brand", cmd.brand());
        payload.put("occurredAt", UtcTime.MILLIS.format(cmd.occurredAt().toInstant()));
        payload.put("receivedAt", UtcTime.MILLIS.format(receivedAt));
        payload.put("ecus", cmd.ecus().stream().map(ecu -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ecuId", ecu.ecuId());
            if (ecu.swVersion() != null) item.put("swVersion", ecu.swVersion());
            item.put("dtcCodes", ecu.dtcCodes());
            return item;
        }).toList());
        try {
            return new OutboxDraft(eventId, mapper.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize outbox payload", exception);
        }
    }
}
