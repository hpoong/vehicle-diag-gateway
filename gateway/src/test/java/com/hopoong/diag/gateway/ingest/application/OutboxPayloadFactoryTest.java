package com.hopoong.diag.gateway.ingest.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import com.hopoong.diag.gateway.ingest.domain.DiagnosticResultCommand;
import com.hopoong.diag.gateway.ingest.domain.EcuCommand;

class OutboxPayloadFactoryTest {
    @Test void receiverContractUsesUtcAndOmitsOptionalFields() throws Exception {
        var mapper = new ObjectMapper();
        var cmd = new DiagnosticResultCommand("d", "e", "X", "VIN", "session",
                OffsetDateTime.parse("2026-11-06T08:10:00+09:00"),
                List.of(new EcuCommand("BMS", null, List.of("P0A80"), 0)));
        var draft = new OutboxPayloadFactory(mapper).create(cmd, 7, Instant.parse("2026-11-06T00:00:03.120456Z"), "STANDARD-v1");
        var json = mapper.readTree(draft.payloadJson());
        assertThat(UUID.fromString(json.get("eventId").asText())).isEqualTo(draft.eventId());
        assertThat(json.get("occurredAt").asText()).isEqualTo("2026-11-05T23:10:00.000Z");
        assertThat(json.get("receivedAt").asText()).isEqualTo("2026-11-06T00:00:03.120Z");
        assertThat(json.get("source").get("resultId").asLong()).isEqualTo(7);
        assertThat(json.get("source").get("deviceEventId").asText()).isEqualTo("e");
        assertThat(json.has("sessionId")).isFalse();
        assertThat(json.get("ecus").get(0).has("swVersion")).isFalse();
        assertThat(json.get("ecus").get(0).has("requestIndex")).isFalse();
    }
}
