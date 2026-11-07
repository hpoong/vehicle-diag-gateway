package com.hopoong.diag.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hopoong.diag.gateway.ingest.*;
import com.hopoong.diag.gateway.delivery.OutboxMapper;
import com.hopoong.diag.gateway.policy.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(IngestFlowIT.Policies.class)
@ExtendWith(OutputCaptureExtension.class)
class IngestFlowIT {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired IngestService service;
    @Autowired OutboxMapper outbox;
    @TestConfiguration static class Policies {
        @Bean ResultValidationPolicy noop() { return new ResultValidationPolicy() {
            public String code() { return "NOOP-v1"; }
            public Set<String> brands() { return Set.of("NOOP"); }
            public List<Violation> validate(DiagnosticResultCommand cmd) { return List.of(); }
        }; }
    }
    String body(String event) {
        return """
            {"deviceId":"flow-device","eventId":"%s","brand":"ZETA","vin":"1HGCM82633A004352",
             "sessionId":"session","occurredAt":"2026-11-06T08:10:00+09:00",
             "ecus":[{"ecuId":"VCU","dtcCodes":[]},{"ecuId":"BMS","dtcCodes":["P0AFA","P0A80"]}]}
            """.formatted(event);
    }
    org.springframework.test.web.servlet.ResultActions postBody(String body) throws Exception {
        return mvc.perform(post("/api/v1/diagnostic-results").contentType(MediaType.APPLICATION_JSON).content(body));
    }
    @Test void acceptsQueriesAndReturnsCurrentDeliveryOnDuplicate() throws Exception {
        String event = UUID.randomUUID().toString(), body = body(event);
        var response = json.readTree(postBody(body).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.policyCode").value("STANDARD-v1"))
                .andExpect(jsonPath("$.data.delivery.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString()).get("data");
        long id = response.get("resultId").asLong();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ecu_observation WHERE result_id=?", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT raw_payload->>'eventId' FROM diagnostic_result WHERE id=?", String.class, id)).isEqualTo(event);
        assertThat(jdbc.queryForObject("SELECT payload->>'eventId'=event_id::text FROM outbox_event WHERE result_id=?", Boolean.class, id)).isTrue();
        mvc.perform(get("/api/v1/diagnostic-results/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ecus[0].ecuId").value("BMS"))
                .andExpect(jsonPath("$.data.ecus[0].dtcCodes[0]").value("P0A80"))
                .andExpect(jsonPath("$.data.delivery.attemptCount").value(0))
                .andExpect(jsonPath("$.data.delivery.sentAt").isEmpty());
        jdbc.update("UPDATE outbox_event SET status='SENT', sent_at=now(), attempt_count=1, last_outcome='SUCCESS' WHERE result_id=?", id);
        postBody(body).andExpect(status().isOk()).andExpect(jsonPath("$.data.resultId").value(id))
                .andExpect(jsonPath("$.data.status").value("DUPLICATE"))
                .andExpect(jsonPath("$.data.delivery.status").value("SENT"))
                .andExpect(jsonPath("$.data.delivery.eventId").value(response.get("delivery").get("eventId").asText()));
        mvc.perform(get("/api/v1/diagnostic-results/" + id)).andExpect(jsonPath("$.data.delivery.lastOutcome").value("SUCCESS"))
                .andExpect(jsonPath("$.data.delivery.sentAt").isNotEmpty());
        postBody(body.replace("2026-11-06T08:10:00+09:00", "2026-11-05T23:10:00Z")
                .replace("[\"P0AFA\",\"P0A80\"]", "[\"P0A80\",\"P0AFA\"]")).andExpect(status().isOk());
        postBody(body.replace("P0AFA", "P0123")).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/diagnostic-results/999999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/diagnostic-results/abc")).andExpect(status().isBadRequest());
    }
    @Test void brandPolicyCodeAndSortedObservationsArePersisted() throws Exception {
        String event = UUID.randomUUID().toString();
        String body = body(event).replace("ZETA", "BRAND_A").replace("\"ecuId\":", "\"swVersion\":\"1.0\",\"ecuId\":");
        var response = json.readTree(postBody(body).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.policyCode").value("BRAND_A-v1"))
                .andReturn().getResponse().getContentAsString()).get("data");
        long id=response.get("resultId").asLong();
        assertThat(jdbc.queryForObject("SELECT policy_code FROM diagnostic_result WHERE id=?", String.class, id)).isEqualTo("BRAND_A-v1");
        assertThat(jdbc.queryForObject("SELECT dtc_codes::text FROM ecu_observation WHERE result_id=? AND ecu_id='BMS'", String.class, id))
                .isEqualTo("{P0A80,P0AFA}");
    }
    @Test void commonRulesCannotBeBypassedAndConflictRunsFirst() throws Exception {
        String event = UUID.randomUUID().toString();
        String body = body(event);
        postBody(body).andExpect(status().isAccepted());
        String empty = body.substring(0, body.indexOf("\"ecus\"")) + "\"ecus\":[]}";
        postBody(empty).andExpect(status().isConflict());
        empty = empty.replace(event, UUID.randomUUID().toString()).replace("ZETA", "NOOP");
        postBody(empty).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:diag-gateway:error:common-rule-violation"));
        String rejectedEvent = json.readTree(empty).get("eventId").asText();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnostic_result WHERE event_id=?", Integer.class, rejectedEvent)).isZero();
    }
    @Test void fiveConcurrentRequestsAcceptExactlyOnce() throws Exception {
        String body = body(UUID.randomUUID().toString());
        var pool = Executors.newFixedThreadPool(5);
        var ready = new CountDownLatch(5); var start = new CountDownLatch(1);
        try {
            List<Future<org.springframework.test.web.servlet.MvcResult>> futures = new ArrayList<>();
            for (int i=0; i<5; i++) futures.add(pool.submit(() -> {
                ready.countDown(); start.await();
                return postBody(body).andReturn();
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            List<Integer> statuses = new ArrayList<>(); Set<Long> ids = new HashSet<>();
            for (var future : futures) {
                var result = future.get(30, TimeUnit.SECONDS);
                statuses.add(result.getResponse().getStatus());
                ids.add(json.readTree(result.getResponse().getContentAsString()).get("data").get("resultId").asLong());
            }
            assertThat(statuses).containsExactlyInAnyOrder(202,200,200,200,200); assertThat(ids).hasSize(1);
            long id = ids.iterator().next();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE result_id=?", Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ecu_observation WHERE result_id=?", Integer.class, id)).isEqualTo(2);
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    @Test void latestDoesNotRegressAndEqualTimestampUsesResultId() throws Exception {
        String vin="2HGCM82633A004352";
        var first = service.ingest(json.readValue(body(UUID.randomUUID().toString()).replace("1HGCM82633A004352",vin), DiagnosticResultRequest.class));
        var older = service.ingest(json.readValue(body(UUID.randomUUID().toString()).replace("1HGCM82633A004352",vin)
                .replace("08:10:00", "07:10:00"), DiagnosticResultRequest.class));
        assertThat(older.outcome()).isEqualTo(IngestResult.Outcome.ACCEPTED);
        assertThat(jdbc.queryForObject("SELECT result_id FROM vehicle_ecu_latest WHERE vin=? AND ecu_id='BMS'", Long.class, vin)).isEqualTo(first.resultId());
        var equal = service.ingest(json.readValue(body(UUID.randomUUID().toString()).replace("1HGCM82633A004352",vin)
                .replace("[\"P0AFA\",\"P0A80\"]", "[]"), DiagnosticResultRequest.class));
        assertThat(jdbc.queryForObject("SELECT result_id FROM vehicle_ecu_latest WHERE vin=? AND ecu_id='BMS'", Long.class, vin)).isEqualTo(equal.resultId());
        assertThat(jdbc.queryForObject("SELECT cardinality(dtc_codes) FROM vehicle_ecu_latest WHERE vin=? AND ecu_id='BMS'", Integer.class, vin)).isZero();
        var newer = service.ingest(json.readValue(body(UUID.randomUUID().toString()).replace("1HGCM82633A004352",vin)
                .replace("08:10:00", "09:10:00"), DiagnosticResultRequest.class));
        assertThat(jdbc.queryForObject("SELECT result_id FROM vehicle_ecu_latest WHERE vin=? AND ecu_id='BMS'", Long.class, vin)).isEqualTo(newer.resultId());

    }
    @Test void mismatchedOutboxEventIsRejectedByDatabase() throws Exception {
        var result = service.ingest(json.readValue(body(UUID.randomUUID().toString()), DiagnosticResultRequest.class));
        jdbc.update("DELETE FROM outbox_event WHERE result_id=?", result.resultId());
        assertThatThrownBy(() -> outbox.insert(result.resultId(), UUID.randomUUID().toString(), "{\"eventId\":\""+UUID.randomUUID()+"\"}"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("ck_outbox_payload_event");
    }
    @Test void decisionLogsAndRequestIdAreCorrelatedAndMdcIsCleared(CapturedOutput output) throws Exception {
        String event=UUID.randomUUID().toString(), requestId=UUID.randomUUID().toString();
        String body=body(event).replace("flow-device", "log-device");
        var accepted=postBody(body).andExpect(status().isAccepted()).andReturn();
        UUID.fromString(accepted.getResponse().getHeader("X-Request-Id"));
        postBody(body).andExpect(status().isOk());
        postBody(body.replace("P0AFA", "P0123")).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/diagnostic-results").header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID().toString()).replace("ZETA", "BRAND_A")))
                .andExpect(status().isUnprocessableEntity()).andExpect(header().string("X-Request-Id", requestId))
                .andExpect(jsonPath("$.requestId").value(requestId));
        String commonBody=body(UUID.randomUUID().toString());
        postBody(commonBody.substring(0, commonBody.indexOf("\"ecus\"")) + "\"ecus\":[]}")
                .andExpect(status().isUnprocessableEntity());
        postBody("{").andExpect(status().isBadRequest());
        assertThat(output.getOut()).contains("INGEST_ACCEPTED", "INGEST_DUPLICATE", "INGEST_CONFLICT",
                "INGEST_REJECTED(policy-violation)", "INGEST_REJECTED(invalid-request)", "deviceId:log-device");
        assertThat(output.getOut()).doesNotContain("P0A80");
        for (String decision : List.of("INGEST_ACCEPTED", "INGEST_DUPLICATE", "INGEST_CONFLICT",
                "INGEST_REJECTED(policy-violation)", "INGEST_REJECTED(common-rule-violation)", "INGEST_REJECTED(invalid-request)")) {
            assertThat(output.getOut().lines().filter(line -> line.contains(decision)).count()).isEqualTo(1);
        }

        assertThat(org.slf4j.MDC.get("deviceId")).isNull();
        assertThat(org.slf4j.MDC.get("eventId")).isNull();
        assertThat(org.slf4j.MDC.get("resultId")).isNull();
    }
}
