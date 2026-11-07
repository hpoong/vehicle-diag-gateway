package com.hopoong.diag.receiver;

import com.hopoong.diag.testsupport.QualityEventFixture;
import com.hopoong.diag.receiver.quality.application.PayloadHasher;
import com.hopoong.diag.receiver.quality.web.QualityEventRequest;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class QualityEventIT extends ReceiverDatabaseITSupport {
    @Autowired PayloadHasher hasher;

    @Test
    void appliesOnceThenRecognizesDuplicateRegardlessOfKeyOrderAndRejectsConflict() throws Exception {
        String id = UUID.randomUUID().toString();
        String body = QualityEventFixture.sample(id);
        var first = post(body);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody().at("/data/result").asText()).isEqualTo("APPLIED");
        assertThat(first.getBody().at("/data/eventId").asText()).isEqualTo(id);
        assertThat(first.getBody().at("/data/caseCount").asInt()).isEqualTo(3);
        assertThat(post(body).getBody().at("/data/result").asText()).isEqualTo("DUPLICATE");
        var reordered = new TreeMap<String, Object>();
        mapper.readTree(body).fields().forEachRemaining(e -> reordered.put(e.getKey(), e.getValue()));
        assertThat(post(mapper.writeValueAsString(reordered)).getBody().at("/data/result").asText()).isEqualTo("DUPLICATE");
        var row = jdbc.queryForMap("SELECT payload_hash,source_result_id,vin,case_count FROM received_event");
        assertThat(row).containsEntry("payload_hash", hasher.hash(mapper.readValue(body, QualityEventRequest.class)))
                .containsEntry("source_result_id", 42L).containsEntry("vin", "KMHXX00XXXX000001").containsEntry("case_count", 3);
        var cases = jdbc.queryForList("SELECT event_id::text,vin,ecu_id,sw_version,dtc_code,occurred_at FROM quality_case ORDER BY ecu_id,dtc_code");
        assertThat(cases).hasSize(3);
        assertThat(cases).allSatisfy(c -> {
            assertThat(c).containsEntry("event_id", id).containsEntry("vin", "KMHXX00XXXX000001");
            assertThat(((java.sql.Timestamp)c.get("occurred_at")).toInstant())
                    .isEqualTo(OffsetDateTime.parse("2026-11-05T23:10:00Z").toInstant());
        });
        assertThat(cases.get(0)).containsEntry("ecu_id", "BMS").containsEntry("sw_version", "1.2").containsEntry("dtc_code", "P0A80");
        assertThat(cases.get(2)).containsEntry("ecu_id", "VCU").containsEntry("sw_version", null).containsEntry("dtc_code", "P0001");
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(body);
        ((com.fasterxml.jackson.databind.node.ArrayNode)changed.get("ecus")).addObject().put("ecuId", "ABS").putArray("dtcCodes").add("C0035");
        var conflict = post(changed.toString());
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody().path("type").asText()).isEqualTo("urn:diag-receiver:error:event-conflict");
        assertThat(jdbc.queryForObject("SELECT payload_hash FROM received_event", String.class)).isEqualTo(row.get("payload_hash"));
        assertThat(count("received_event")).isEqualTo(1);
        assertThat(count("quality_case")).isEqualTo(3);
        assertThat(duplicateGroups("quality_case")).isZero();
    }

    @Test
    void tenConcurrentRequestsApplyExactlyOnce() throws Exception {
        String body = QualityEventFixture.sample(UUID.randomUUID().toString());
        var pool = Executors.newFixedThreadPool(10);
        var ready = new CountDownLatch(10);
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 10; i++) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                var response = post(body);
                assertThat(response.getStatusCode().value()).isEqualTo(200);
                return response.getBody().at("/data/result").asText();
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new ArrayList<String>();
            for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            assertThat(Collections.frequency(results, "APPLIED")).isEqualTo(1);
            assertThat(Collections.frequency(results, "DUPLICATE")).isEqualTo(9);
            assertThat(count("received_event")).isEqualTo(1);
            assertThat(count("quality_case")).isEqualTo(3);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test
    void acceptsEmptyEcusWithoutExecutingEmptyInsert() {
        var response = post(QualityEventFixture.body(UUID.randomUUID().toString(), 1));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().at("/data/caseCount").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT case_count FROM received_event", Integer.class)).isZero();
        assertThat(count("quality_case")).isZero();
    }
}
