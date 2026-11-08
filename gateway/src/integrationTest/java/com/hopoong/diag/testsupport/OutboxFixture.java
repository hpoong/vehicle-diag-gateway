package com.hopoong.diag.testsupport;

import com.hopoong.diag.gateway.ingest.application.*;
import com.hopoong.diag.gateway.ingest.domain.*;
import com.hopoong.diag.gateway.ingest.persistence.*;
import com.hopoong.diag.gateway.ingest.web.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.hopoong.diag.gateway.ingest.application.IngestService;
import com.hopoong.diag.gateway.ingest.web.DiagnosticResultRequest;
import com.hopoong.diag.gateway.ingest.web.EcuRequest;

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
    public void markSent(long id) {
        jdbc.update("UPDATE outbox_event SET status='SENT', sent_at=now(), last_outcome='SUCCESS', dead_reason=NULL, dead_at=NULL, claim_token=NULL, locked_by=NULL, locked_until=NULL WHERE id=?", id);
    }
    public void markDead(long id, String reason, int attemptCount) {
        jdbc.update("UPDATE outbox_event SET status='DEAD', dead_reason=?, dead_at=now(), sent_at=NULL, attempt_count=?, last_outcome=?, claim_token=NULL, locked_by=NULL, locked_until=NULL WHERE id=?",
                reason, attemptCount, "MAX_ATTEMPTS".equals(reason) ? "RETRYABLE" : "NON_RETRYABLE", id);
    }
    public void reset() {
        jdbc.execute("TRUNCATE ops_action_log, delivery_attempt, outbox_event, campaign_vehicle, campaign, vehicle_part, vehicle_ecu_latest, ecu_observation, diagnostic_result RESTART IDENTITY CASCADE");
    }
}
