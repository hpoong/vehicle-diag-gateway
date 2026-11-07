package com.hopoong.diag.testsupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;

public final class QualityEventFixture {
    private static final JsonMapper MAPPER = new JsonMapper();
    private QualityEventFixture() {}
    public record Ecu(String ecuId, String swVersion, List<String> dtcCodes) {}
    public static String body(String eventId, long resultId, Ecu... ecus) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("eventId", eventId);
        payload.put("source", Map.of("system", "diag-gateway", "resultId", resultId,
                "deviceId", "device-1", "deviceEventId", "evt-1", "policyCode", "A-v1"));
        payload.put("vin", "KMHXX00XXXX000001");
        payload.put("brand", "A");
        payload.put("occurredAt", "2026-11-05T23:10:00.000Z");
        payload.put("receivedAt", "2026-11-05T23:10:01.000Z");
        payload.put("ecus", Arrays.stream(ecus).map(ecu -> {
            var map = new LinkedHashMap<String, Object>();
            map.put("ecuId", ecu.ecuId());
            if (ecu.swVersion() != null) map.put("swVersion", ecu.swVersion());
            map.put("dtcCodes", ecu.dtcCodes());
            return map;
        }).toList());
        try { return MAPPER.writeValueAsString(payload); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
    public static String sample(String eventId) {
        return body(eventId, 42, new Ecu("BMS", "1.2", List.of("P0A80", "P0AFA")),
                new Ecu("VCU", null, List.of("P0001")));
    }
}
