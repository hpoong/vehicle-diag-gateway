package com.hopoong.diag.testsupport;

import com.hopoong.diag.gateway.ingest.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public final class OutboxFixture {
    public static final String VIN = "1HGCM82633A004352";
    private final IngestService ingest;
    private final JdbcTemplate jdbc;
    public OutboxFixture(IngestService ingest, JdbcTemplate jdbc) { this.ingest = ingest; this.jdbc = jdbc; }
    public List<Long> createPending(int count) {
        var ids = new ArrayList<Long>();
        for (int i=0; i<count; i++) {
            var result = ingest.ingest(new DiagnosticResultRequest("delivery-device", UUID.randomUUID().toString(),
                    "ZETA", VIN, "session", "2026-11-06T08:10:00+09:00",
                    List.of(new EcuRequest("BMS", null, List.of("P0A80")))));
            ids.add(jdbc.queryForObject("SELECT id FROM outbox_event WHERE result_id=?", Long.class, result.resultId()));
        }
        return ids;
    }
    public void reset() {
        jdbc.execute("TRUNCATE ops_action_log, delivery_attempt, outbox_event, vehicle_ecu_latest, ecu_observation, diagnostic_result RESTART IDENTITY CASCADE");
    }
}
