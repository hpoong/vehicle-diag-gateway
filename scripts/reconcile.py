"""gateway DB와 receiver DB를 직접 조회해 누락·중복을 판정한다.

판정 항목은 통합 테스트의 ReconcileAssertions(I1~I6)와 같다.
  I1 결과 수 = 진단 outbox 수
  I2 PENDING + SENT + DEAD = outbox 수
  I3 SENT인데 receiver에 없는 이벤트 0건
  I4 (PENDING 0일 때) 누락 0건, SENT가 아닌데 receiver에 반영된 이벤트 0건
  I5 이벤트별 (ECU, DTC) 쌍 수 = receiver case_count = quality_case 수
  I6 (VIN, ECU)별 최신 상태가 가장 늦은 occurred_at을 가리킴
추가로 --expect-results N을 주면 E1(결과 수 = N)을 함께 판정한다.

    python3 -m scripts.reconcile
    python3 -m scripts.reconcile --mode naive --json
"""
import argparse
import json
import sys

from scripts.common import GATEWAY_DSN, RECEIVER_DSN, connect


def _rows(conn, sql):
    with conn.cursor() as cur:
        cur.execute(sql)
        return cur.fetchall()


def _one(conn, sql):
    return _rows(conn, sql)[0][0]


def _ids(conn, sql):
    return {r[0] for r in _rows(conn, sql)}


def _detail(ids):
    return "count={} eventIds={}".format(len(ids), sorted(ids)[:10])


def reconcile(mode="standard", allow_dead=False, expect_results=None):
    """판정 결과 dict. mode는 standard | naive."""
    naive = mode == "naive"
    with connect(GATEWAY_DSN) as g, connect(RECEIVER_DSN) as r:
        results = _one(g, "SELECT count(*) FROM diagnostic_result")
        diagnostic_outbox = _one(g, "SELECT count(*) FROM outbox_event WHERE event_type='DIAGNOSTIC_RESULT'")
        diagnostic_pending = _one(g, "SELECT count(*) FROM outbox_event WHERE status='PENDING' AND event_type='DIAGNOSTIC_RESULT'")
        status = dict(_rows(g, "SELECT status, count(*) FROM outbox_event GROUP BY status"))
        outbox = sum(status.values())
        pending, sent, dead = (status.get(s, 0) for s in ("PENDING", "SENT", "DEAD"))
        received_ids = _ids(r, "SELECT event_id::text FROM received_event")
        sent_ids = _ids(g, "SELECT event_id::text FROM outbox_event WHERE status='SENT' AND event_type='DIAGNOSTIC_RESULT'")
        dead_ids = _ids(g, "SELECT event_id::text FROM outbox_event WHERE status='DEAD' AND event_type='DIAGNOSTIC_RESULT'")

        v = {}

        def verdict(key, ok, detail):
            v[key] = {"status": "PASS" if ok else "FAIL", "detail": detail}

        verdict("I1", results == diagnostic_outbox, "results={} diagnosticOutbox={}".format(results, diagnostic_outbox))
        verdict("I2", pending + sent + dead == outbox, "status total={}".format(pending + sent + dead))
        missing = sent_ids - received_ids
        if naive:
            for key in ("I3", "I4", "I5"):
                v[key] = {"status": "SKIP", "detail": "NAIVE"}
        else:
            verdict("I3", not missing, _detail(missing))
            if diagnostic_pending > 0:
                v["I4"] = {"status": "SKIP", "detail": "PENDING={}".format(diagnostic_pending)}
            else:
                unexpected = received_ids - sent_ids - (dead_ids if allow_dead else set())
                verdict("I4", not missing and not unexpected,
                        "missing {} unexpected {}".format(_detail(missing), _detail(unexpected)))
            pairs = dict(_rows(g, """
                SELECT ob.event_id::text, coalesce(sum(cardinality(o.dtc_codes)), 0)
                FROM outbox_event ob LEFT JOIN ecu_observation o ON o.result_id = ob.result_id
                WHERE ob.event_type='DIAGNOSTIC_RESULT' GROUP BY ob.event_id"""))
            mismatches = set()
            for event_id, case_count, cases in _rows(r, """
                    SELECT r.event_id::text, r.case_count, count(c.id)
                    FROM received_event r LEFT JOIN quality_case c ON c.event_id = r.event_id
                    GROUP BY r.event_id, r.case_count"""):
                if pairs.get(event_id) != case_count or case_count != cases:
                    mismatches.add(event_id)
            verdict("I5", not mismatches, _detail(mismatches))

        missing_latest, wrong_latest = _rows(g, """
            WITH mx AS (
                SELECT r.vin, o.ecu_id, max(r.occurred_at) AS max_occurred
                FROM diagnostic_result r JOIN ecu_observation o ON o.result_id = r.id
                GROUP BY r.vin, o.ecu_id)
            SELECT count(*) FILTER (WHERE l.vin IS NULL),
                   count(*) FILTER (WHERE l.occurred_at <> mx.max_occurred)
            FROM mx LEFT JOIN vehicle_ecu_latest l ON l.vin = mx.vin AND l.ecu_id = mx.ecu_id""")[0]
        verdict("I6", missing_latest == 0 and wrong_latest == 0,
                "missing_latest={} wrong_latest={}".format(missing_latest, wrong_latest))
        if expect_results is not None:
            verdict("E1", results == expect_results, "results={} expected={}".format(results, expect_results))

        attempts = dict(_rows(g, "SELECT outcome, count(*) FROM delivery_attempt GROUP BY outcome"))
        workers = dict(_rows(g, """
            SELECT split_part(worker_id, ':', 1), count(*) FROM delivery_attempt
            WHERE outcome = 'SUCCESS' GROUP BY 1"""))
        late = dict(_rows(g, """
            SELECT late_outcome, count(*) FROM delivery_attempt
            WHERE late_outcome IS NOT NULL GROUP BY 1"""))
        table = "quality_case_naive" if naive else "quality_case"
        dup1 = _one(r, "SELECT count(*) FROM (SELECT 1 FROM {} GROUP BY event_id, ecu_id, dtc_code "
                       "HAVING count(*) > 1) d".format(table))
        cases = _one(r, "SELECT count(*) FROM {}".format(table))

    failed = [k for k, x in v.items() if x["status"] == "FAIL"]
    report = {
        "mode": mode.upper(),
        "results": results, "outbox": outbox, "pending": pending, "sent": sent, "dead": dead,
        "received": len(received_ids), "cases": cases, "dup1": dup1,
        "attempts": dict(sorted(attempts.items())),
        "lateOutcomes": late,
        "successByInstance": workers,
        "verdicts": dict(sorted(v.items())),
        "ok": not failed,
        "failed": failed,
    }
    report["line"] = line(report)
    return report


def line(report, scenario="-"):
    """통합 테스트의 SCENARIO_RESULT 줄과 같은 형식."""
    verdicts = " ".join("{}={}".format(k, x["status"]) for k, x in report["verdicts"].items())
    attempts = "{" + ", ".join("{}={}".format(k, n) for k, n in report["attempts"].items()) + "}"
    return ("SCENARIO_RESULT scenario={} mode={mode} results={results} outbox={outbox} pending={pending} "
            "sent={sent} dead={dead} received={received} dup1={dup1} attempts={a} {v}").format(
        scenario, a=attempts, v=verdicts, **report)


def main(argv=None):
    p = argparse.ArgumentParser(prog="python3 -m scripts.reconcile", description="gateway·receiver DB 대조")
    p.add_argument("--mode", choices=["standard", "naive"], default="standard")
    p.add_argument("--allow-dead", action="store_true", help="DEAD 건이 receiver에 반영된 것을 허용")
    p.add_argument("--expect-results", type=int, default=None, help="저장돼야 하는 결과 수 (E1)")
    p.add_argument("--scenario", default="-", help="출력 줄의 시나리오 이름")
    p.add_argument("--json", action="store_true")
    args = p.parse_args(argv)
    report = reconcile(args.mode, args.allow_dead, args.expect_results)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(line(report, args.scenario))
        for key, x in report["verdicts"].items():
            print("  {} {:<4} {}".format(key, x["status"], x["detail"]))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
