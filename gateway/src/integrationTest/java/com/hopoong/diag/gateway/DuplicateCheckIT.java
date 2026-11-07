package com.hopoong.diag.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hopoong.diag.gateway.ingest.application.DiagnosticResultNormalizer;
import com.hopoong.diag.gateway.ingest.web.DiagnosticResultRequest;
import com.hopoong.diag.gateway.ingest.application.IngestResult;
import com.hopoong.diag.gateway.ingest.application.IngestService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class DuplicateCheckIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired DiagnosticResultNormalizer normalizer;
    @Autowired IngestService service;
    @Autowired MockMvc mvc;

    private String request(String eventId) {
        return """
                {"deviceId":"device","eventId":"%s","brand":"BRAND","vin":"1HGCM82633A004352",
                 "sessionId":"session","occurredAt":"2026-11-06T08:10:00+09:00",
                 "ecus":[{"ecuId":"VCU","dtcCodes":[]},{"ecuId":"BMS","dtcCodes":["P0AFA","P0A80"]}]}
                """.formatted(eventId);
    }

    private DiagnosticResultRequest parse(String json) throws Exception {
        return mapper.readValue(json, DiagnosticResultRequest.class);
    }

    private long insert(String json, UUID deliveryId) throws Exception {
        var request = parse(json);
        var normalized = normalizer.normalize(request);
        Long id = jdbc.queryForObject("""
                INSERT INTO diagnostic_result (device_id, event_id, payload_hash, brand, vin, session_id,
                    occurred_at, policy_code, raw_payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'BRAND-v1', CAST(? AS jsonb)) RETURNING id
                """, Long.class, request.deviceId(), request.eventId(), normalized.payloadHash(), request.brand(),
                request.vin(), request.sessionId(), normalized.command().occurredAt(), normalized.rawPayload());
        if (deliveryId != null) {
            jdbc.update("INSERT INTO outbox_event (event_id, result_id, payload) VALUES (?, ?, CAST(? AS jsonb))",
                    deliveryId, id, "{\"eventId\":\"" + deliveryId + "\"}");
        }
        return id;
    }

    @Test
    void equivalentRequestsReturnExistingResult() throws Exception {
        String event = UUID.randomUUID().toString();
        String json = request(event);
        UUID delivery = UUID.randomUUID();
        long id = insert(json, delivery);
        String equivalent = """
                {"vin":"1HGCM82633A004352","sessionId":"session","brand":"BRAND",
                 "eventId":"%s","deviceId":"device","occurredAt":"2026-11-05T23:10:00Z",
                 "ecus":[{"dtcCodes":["P0A80","P0AFA"],"ecuId":"BMS"},
                         {"dtcCodes":[],"ecuId":"VCU","swVersion":null}]}
                """.formatted(event);
        for (String input : new String[]{json, equivalent}) {
            IngestResult result = service.ingest(parse(input));
            assertThat(result.outcome()).isEqualTo(IngestResult.Outcome.DUPLICATE);
            assertThat(result.resultId()).isEqualTo(id);
            assertThat(result.deviceId()).isEqualTo("device");
            assertThat(result.eventId()).isEqualTo(event);
            assertThat(result.policyCode()).isEqualTo("BRAND-v1");
            assertThat(result.receivedAt()).isNotNull();
            assertThat(result.deliveryEventId()).isEqualTo(delivery);
            assertThat(result.deliveryStatus()).isEqualTo("PENDING");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnostic_result WHERE event_id = ?", Integer.class, event))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE result_id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void changedPayloadReturnsConflictWithoutModifyingStoredResult() throws Exception {
        String event = UUID.randomUUID().toString();
        String json = request(event);
        long id = insert(json, UUID.randomUUID());
        String hash = normalizer.normalize(parse(json)).payloadHash();
        mvc.perform(post("/api/v1/diagnostic-results").contentType(MediaType.APPLICATION_JSON)
                        .content(json.replace("\"P0AFA\"", "\"P0AFA\",\"P0123\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:diag-gateway:error:event-conflict"));
        assertThat(jdbc.queryForObject("SELECT payload_hash FROM diagnostic_result WHERE id = ?", String.class, id))
                .isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnostic_result WHERE event_id = ?", Integer.class, event))
                .isEqualTo(1);
    }

    @Test
    void missingOutboxStillRecognizesResultAndLogsError(CapturedOutput output) throws Exception {
        String json = request(UUID.randomUUID().toString());
        long id = insert(json, null);
        IngestResult result = service.ingest(parse(json));
        assertThat(result.outcome()).isEqualTo(IngestResult.Outcome.DUPLICATE);
        assertThat(result.resultId()).isEqualTo(id);
        assertThat(result.deliveryEventId()).isNull();
        assertThat(result.deliveryStatus()).isNull();
        assertThat(output).contains("INGEST_OUTBOX_MISSING resultId=" + id);
    }
}
