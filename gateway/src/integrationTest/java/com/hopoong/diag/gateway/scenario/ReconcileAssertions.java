package com.hopoong.diag.gateway.scenario;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.hopoong.diag.gateway.scenario.ReconcileReport.Status.*;

/** 서비스 간 DB 대조는 통합 테스트에서만 수행한다. */
public final class ReconcileAssertions {
    public enum Mode { STANDARD, NAIVE }
    private final JdbcTemplate gateway;
    private final JdbcTemplate receiver;
    public ReconcileAssertions(JdbcTemplate gateway, JdbcTemplate receiver) {
        this.gateway = gateway; this.receiver = receiver;
    }
    private int count(String sql) { return gateway.queryForObject(sql, Integer.class); }
    private Set<String> ids(JdbcTemplate jdbc, String sql) { return new TreeSet<>(jdbc.queryForList(sql, String.class)); }
    private static Set<String> minus(Set<String> a, Set<String> b) {
        var difference = new TreeSet<>(a); difference.removeAll(b); return difference;
    }
    private static String detail(Set<String> ids) { return "count=" + ids.size() + " eventIds=" + ids.stream().limit(10).toList(); }
    private static void verdict(Map<String, ReconcileReport.Verdict> v, String id, boolean pass, String detail) {
        v.put(id, new ReconcileReport.Verdict(pass ? PASS : FAIL, detail));
    }
    public ReconcileReport check(Mode mode, Set<String> allowedDeadEventIds) {
        int results = count("SELECT count(*) FROM diagnostic_result");
        int outbox = count("SELECT count(*) FROM outbox_event");
        int pending = count("SELECT count(*) FROM outbox_event WHERE status='PENDING'");
        int sent = count("SELECT count(*) FROM outbox_event WHERE status='SENT'");
        int dead = count("SELECT count(*) FROM outbox_event WHERE status='DEAD'");
        var receivedIds = ids(receiver, "SELECT event_id::text FROM received_event");
        var sentIds = ids(gateway, "SELECT event_id::text FROM outbox_event WHERE status='SENT'");
        var deadIds = ids(gateway, "SELECT event_id::text FROM outbox_event WHERE status='DEAD'");
        var v = new TreeMap<String, ReconcileReport.Verdict>();
        verdict(v, "I1", results == outbox, "results=" + results + " outbox=" + outbox);
        verdict(v, "I2", pending + sent + dead == outbox, "status total=" + (pending + sent + dead));
        if (mode == Mode.NAIVE) {
            for (String id : List.of("I3", "I4", "I5")) v.put(id, new ReconcileReport.Verdict(SKIP, "NAIVE"));
        } else {
            var missing = minus(sentIds, receivedIds);
            verdict(v, "I3", missing.isEmpty(), detail(missing));
            if (pending > 0) v.put("I4", new ReconcileReport.Verdict(SKIP, "PENDING=" + pending));
            else {
                deadIds.retainAll(allowedDeadEventIds);
                var unexpected = minus(minus(receivedIds, sentIds), deadIds);
                verdict(v, "I4", missing.isEmpty() && unexpected.isEmpty(), "missing " + detail(missing) + " unexpected " + detail(unexpected));
            }
            var pairs = new HashMap<String, Long>();
            gateway.query("SELECT ob.event_id::text, coalesce(sum(cardinality(o.dtc_codes)),0) AS pairs FROM outbox_event ob LEFT JOIN ecu_observation o ON o.result_id=ob.result_id GROUP BY ob.event_id",
                    rs -> { pairs.put(rs.getString(1), rs.getLong(2)); });
            var mismatches = new TreeSet<String>();
            receiver.query("SELECT r.event_id::text, r.case_count, count(c.id) FROM received_event r LEFT JOIN quality_case c ON c.event_id=r.event_id GROUP BY r.event_id, r.case_count", rs -> {
                String id = rs.getString(1);
                if (!Objects.equals(pairs.get(id), rs.getLong(2)) || rs.getLong(2) != rs.getLong(3)) mismatches.add(id);
            });
            verdict(v, "I5", mismatches.isEmpty(), detail(mismatches));
        }
        var latest = gateway.queryForMap("""
                WITH mx AS (
                    SELECT r.vin, o.ecu_id, max(r.occurred_at) AS max_occurred
                    FROM diagnostic_result r JOIN ecu_observation o ON o.result_id=r.id GROUP BY r.vin, o.ecu_id
                )
                SELECT count(*) FILTER (WHERE l.vin IS NULL) AS missing_latest,
                       count(*) FILTER (WHERE l.occurred_at <> mx.max_occurred) AS wrong_latest
                FROM mx LEFT JOIN vehicle_ecu_latest l ON l.vin=mx.vin AND l.ecu_id=mx.ecu_id
                """);
        verdict(v, "I6", ((Number) latest.get("missing_latest")).longValue() == 0
                && ((Number) latest.get("wrong_latest")).longValue() == 0, latest.toString());
        var attempts = new TreeMap<String, Integer>();
        gateway.query("SELECT outcome, count(*) FROM delivery_attempt GROUP BY outcome", rs -> { attempts.put(rs.getString(1), rs.getInt(2)); });
        String table = mode == Mode.NAIVE ? "quality_case_naive" : "quality_case";
        long dup = receiver.queryForObject("SELECT count(*) FROM (SELECT 1 FROM " + table
                + " GROUP BY event_id, ecu_id, dtc_code HAVING count(*)>1) d", Long.class);
        return new ReconcileReport(mode, results, outbox, pending, sent, dead, receivedIds.size(), attempts, dup, v);
    }
}
