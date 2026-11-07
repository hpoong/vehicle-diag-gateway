"""장애 시나리오 실행기. 실제 프로세스·실제 HTTP·실제 DB로 S1~S12를 재현하고 대조로 판정한다.

통합 테스트(DeliveryScenarioIT 등)는 같은 규칙을 한 JVM 안에서 흉내 내지만,
여기서는 gateway를 실제로 SIGKILL·SIGSTOP 하고 두 번째 gateway 프로세스를 띄운다.

    python3 -m scripts.scenarios list
    python3 -m scripts.scenarios run S7
    python3 -m scripts.scenarios run all

전제: PostgreSQL 실행 중, bootJar 빌드 완료 (python3 -m scripts.services up && ... build).
시나리오는 시작할 때 DB를 비운다.
"""
import argparse
import json
import sys
import time

from scripts import dbreset, fault, services
from scripts.common import GATEWAY_DSN, GATEWAY_URL, RECEIVER_DSN, connect, http
from scripts.reconcile import line, reconcile
from simulator.payloads import build_plan
from simulator.sender import send_plan


class Stopped(Exception):
    pass


class Context:
    """시나리오 진행 기록. front는 같은 객체를 폴링해 화면에 보여 준다."""

    def __init__(self, on_log=None, stop_event=None):
        self.logs = []
        self.checks = []
        self.step = None
        self.simulation = None
        self.reconcile = None
        self._on_log = on_log
        self._stop = stop_event

    def log(self, msg):
        entry = "{} {}".format(time.strftime("%H:%M:%S"), msg)
        self.logs.append(entry)
        if self._on_log:
            self._on_log(entry)

    def begin(self, step):
        self.step = step
        self.log("▶ " + step)

    def stopped(self):
        return self._stop is not None and self._stop.is_set()

    def sleep(self, sec):
        end = time.time() + sec
        while time.time() < end:
            if self.stopped():
                raise Stopped()
            time.sleep(min(0.25, end - time.time()))

    def check(self, name, ok, detail=""):
        self.checks.append({"name": name, "ok": bool(ok), "detail": str(detail)})
        self.log("{} {} {}".format("✔" if ok else "✘", name, detail))


# ---------------------------------------------------------------- DB·대기 도우미

def counts():
    with connect(GATEWAY_DSN) as g, connect(RECEIVER_DSN) as r, g.cursor() as gc, r.cursor() as rc:
        gc.execute("SELECT status, count(*) FROM outbox_event GROUP BY status")
        status = dict(gc.fetchall())
        gc.execute("SELECT outcome, count(*) FROM delivery_attempt GROUP BY outcome")
        attempts = dict(gc.fetchall())
        rc.execute("SELECT count(*) FROM received_event")
        received = rc.fetchone()[0]
        rc.execute("SELECT count(*) FROM quality_case_naive")
        naive_cases = rc.fetchone()[0]
    return {"PENDING": status.get("PENDING", 0), "SENT": status.get("SENT", 0), "DEAD": status.get("DEAD", 0),
            "attempts": attempts, "received": received, "naiveCases": naive_cases}


def wait_for(ctx, desc, predicate, timeout=60, interval=0.5):
    ctx.log("… 대기: {} (최대 {}s)".format(desc, timeout))
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = counts()
        if predicate(last):
            ctx.log("  충족: PENDING={PENDING} SENT={SENT} DEAD={DEAD} received={received} attempts={attempts}"
                    .format(**last))
            return last
        ctx.sleep(interval)
    raise RuntimeError("대기 시간 초과: {} (마지막 상태 {})".format(desc, last))


def drain(ctx, timeout=90):
    return wait_for(ctx, "PENDING 0건", lambda c: c["PENDING"] == 0, timeout)


def set_fault(ctx, mode, hold_ms=None):
    config = fault.set_fault(mode, hold_ms)
    ctx.log("receiver 장애 모드 = {} (holdMs={})".format(config["mode"], config["holdMs"]))


def ensure(ctx, gateway2=False, naive=False):
    """필요한 프로세스를 맞춘다. receiver 모드가 다르면 다시 띄운다."""
    st = services.status()
    if st["postgres"]["state"] != "up":
        raise RuntimeError("PostgreSQL이 실행 중이 아닙니다 (python3 -m scripts.services up)")
    rec = st["receiver"]
    if rec["state"] == "external":
        ctx.log("receiver가 스크립트 밖에서 실행 중입니다. chaos 프로필이어야 합니다.")
    elif rec["state"] in ("up", "starting", "paused") and bool(rec["options"].get("naive")) != naive:
        ctx.log("receiver 모드 전환 → naive={}".format(naive))
        services.stop("receiver")
        services.start("receiver", naive=naive)
    elif rec["state"] == "down":
        ctx.log("receiver 시작 (naive={})".format(naive))
        services.start("receiver", naive=naive)
    for name, wanted in (("gateway", True), ("gateway2", gateway2)):
        state = st[name]["state"]
        if state == "paused":
            services.resume(name)
        if wanted and state == "down":
            ctx.log("{} 시작".format(name))
            services.start(name)
        elif wanted and state == "starting":
            services.wait_healthy(name)
        elif not wanted and state in ("up", "starting", "paused"):
            ctx.log("{} 정지 (이 시나리오에서는 사용하지 않음)".format(name))
            services.stop(name)


def prepare(ctx, gateway2=False, naive=False):
    ctx.begin("환경 준비")
    ensure(ctx, gateway2=gateway2, naive=naive)
    set_fault(ctx, "NONE")
    truncated = dbreset.reset()
    ctx.log("DB 초기화 (이전 결과 {}건, 수신 {}건)".format(truncated["diagnostic_result"],
                                                     truncated["received_event"]))


def simulate(ctx, **kwargs):
    plan = build_plan(**kwargs)
    ctx.log("시뮬레이터 전송 run={} 계획={} → 저장 기대 {}건".format(plan.run_id, plan.counts(), plan.expected_results))
    report = send_plan(plan, concurrency=8).to_dict()
    ctx.simulation = report
    ctx.log("  응답 {} / {}ms(p95) / 기대와 다른 응답 {}건".format(
        report["byKind"], report["latencyMs"]["p95"], report["unexpectedCount"]))
    ctx.check("단말 응답 코드가 기대와 일치", report["unexpectedCount"] == 0,
              "unexpected={}".format(report["unexpectedCount"]))
    return report


def judge(ctx, scenario, mode="standard", expect_results=None):
    ctx.begin("대조 (reconcile)")
    report = reconcile(mode=mode, expect_results=expect_results)
    report["line"] = line(report, scenario)
    ctx.reconcile = report
    ctx.log(report["line"])
    ctx.check("대조 I1~I6{}".format(" + E1" if expect_results is not None else ""), report["ok"],
              "failed={}".format(report["failed"]) if report["failed"] else "모두 PASS/SKIP")
    return report


# ---------------------------------------------------------------- 시나리오

def s1(ctx):
    prepare(ctx)
    ctx.begin("같은 결과 재전송")
    sim = simulate(ctx, count=40, duplicate_rate=0.3)
    drain(ctx)
    rep = judge(ctx, "S1", expect_results=sim["expectedResults"])
    ctx.check("재전송은 200, 저장은 고유 결과 수만큼", sim["byKind"].get("duplicate", {}).get("200", 0) > 0
              and rep["results"] == sim["expectedResults"], "results={}".format(rep["results"]))


def s2(ctx):
    prepare(ctx)
    ctx.begin("같은 키·다른 내용 전송")
    sim = simulate(ctx, count=30, conflict_rate=0.3)
    drain(ctx)
    rep = judge(ctx, "S2", expect_results=sim["expectedResults"])
    ctx.check("충돌은 409, 기존 결과 유지", sim["byKind"].get("conflict", {}).get("409", 0) > 0
              and rep["results"] == sim["expectedResults"])


def s3(ctx):
    prepare(ctx)
    ctx.begin("과거 결과를 늦게 전송")
    sim = simulate(ctx, count=30, late_rate=0.3)
    drain(ctx)
    rep = judge(ctx, "S3", expect_results=sim["expectedResults"])
    ctx.check("최신 상태가 과거로 돌아가지 않음 (I6)", rep["verdicts"]["I6"]["status"] == "PASS",
              rep["verdicts"]["I6"]["detail"])


def s4(ctx):
    prepare(ctx)
    set_fault(ctx, "DOWN")
    ctx.begin("receiver 중단 중 전송")
    sim = simulate(ctx, count=20)
    wait_for(ctx, "전 건 1회 이상 RETRYABLE", lambda c: c["attempts"].get("RETRYABLE", 0) >= 20, 30)
    ctx.sleep(3)
    ctx.begin("receiver 복구")
    set_fault(ctx, "NONE")
    drain(ctx)
    rep = judge(ctx, "S4", expect_results=sim["expectedResults"])
    ctx.check("실패 건이 자동 재처리되어 DEAD 0건", rep["dead"] == 0 and rep["attempts"].get("RETRYABLE", 0) > 0,
              "attempts={}".format(rep["attempts"]))


def _response_loss(ctx, scenario, naive):
    set_fault(ctx, "DROP_AFTER_COMMIT", 6000)
    ctx.begin("receiver 커밋 후 응답 지연 (gateway readTimeout 5s 초과)")
    sim = simulate(ctx, count=20)
    wait_for(ctx, "UNCERTAIN 4건 이상", lambda c: c["attempts"].get("UNCERTAIN", 0) >= 4, 60)
    ctx.begin("receiver 정상화")
    set_fault(ctx, "NONE")
    drain(ctx, 120)
    return sim, judge(ctx, scenario, mode="naive" if naive else "standard",
                      expect_results=sim["expectedResults"])


def s5(ctx):
    prepare(ctx)
    sim, rep = _response_loss(ctx, "S5", naive=False)
    ctx.check("재전송 발생, 수신 측 중복 반영 0건", rep["attempts"].get("UNCERTAIN", 0) > 0 and rep["dup1"] == 0,
              "UNCERTAIN={} dup1={}".format(rep["attempts"].get("UNCERTAIN", 0), rep["dup1"]))


def s7(ctx):
    prepare(ctx)
    set_fault(ctx, "DROP_AFTER_COMMIT", 4000)
    ctx.begin("전송 중인 gateway 강제 종료 (SIGKILL)")
    sim = simulate(ctx, count=12)
    wait_for(ctx, "receiver가 커밋하고 gateway가 응답 대기 중", lambda c: c["received"] >= 1, 30, 0.2)
    ctx.log("gateway: " + services.kill("gateway"))
    set_fault(ctx, "NONE")
    ctx.sleep(1)
    ctx.begin("gateway 재기동 → lease 만료(10s) 후 재선점")
    ctx.log("gateway: " + services.start("gateway"))
    drain(ctx, 120)
    rep = judge(ctx, "S7", expect_results=sim["expectedResults"])
    ctx.check("남은 선점이 ABANDONED 처리 후 재전송, 중복 0건",
              rep["attempts"].get("ABANDONED", 0) > 0 and rep["dup1"] == 0,
              "attempts={} dup1={}".format(rep["attempts"], rep["dup1"]))


def s8(ctx):
    prepare(ctx, gateway2=True)
    ctx.begin("gateway 2개 인스턴스 동시 전송")
    sim = simulate(ctx, count=200)
    drain(ctx, 120)
    rep = judge(ctx, "S8", expect_results=sim["expectedResults"])
    with connect(GATEWAY_DSN) as g, g.cursor() as cur:
        cur.execute("SELECT count(*) FROM (SELECT outbox_id FROM delivery_attempt WHERE outcome='SUCCESS' "
                    "GROUP BY outbox_id, cycle_no HAVING count(*) > 1) d")
        double = cur.fetchone()[0]
    ctx.check("같은 건을 두 인스턴스가 함께 성공 처리한 경우 0건", double == 0, "double={}".format(double))
    ctx.check("두 인스턴스가 나눠 처리", len(rep["successByInstance"]) >= 2, rep["successByInstance"])


def s9(ctx):
    if not services.CAN_PAUSE:
        raise RuntimeError("S9는 SIGSTOP을 지원하는 macOS·Linux에서만 실행할 수 있습니다")
    prepare(ctx)
    set_fault(ctx, "DROP_AFTER_COMMIT", 3000)
    ctx.begin("gw-1이 응답을 기다리는 중 일시 정지 (SIGSTOP)")
    sim = simulate(ctx, count=8)
    wait_for(ctx, "receiver 커밋 1건 이상", lambda c: c["received"] >= 1, 30, 0.2)
    ctx.log("gateway: " + services.pause("gateway"))
    set_fault(ctx, "NONE")
    try:
        ctx.begin("gw-2 기동 → lease 만료 후 gw-2가 재선점·전송")
        ctx.log("gateway2: " + services.start("gateway2"))
        wait_for(ctx, "ABANDONED 발생", lambda c: c["attempts"].get("ABANDONED", 0) >= 1, 60)
        drain(ctx, 60)
    finally:
        ctx.begin("gw-1 재개 (SIGCONT) → 늦게 도착한 결과 기록 시도")
        ctx.log("gateway: " + services.resume("gateway"))
    ctx.sleep(3)
    drain(ctx, 60)
    rep = judge(ctx, "S9", expect_results=sim["expectedResults"])
    ctx.check("늦은 결과는 late_outcome으로만 남고 상태를 덮어쓰지 않음, 중복 0건",
              sum(rep["lateOutcomes"].values()) > 0 and rep["dup1"] == 0,
              "lateOutcomes={} dup1={}".format(rep["lateOutcomes"], rep["dup1"]))


def s10(ctx):
    prepare(ctx)
    set_fault(ctx, "DOWN")
    ctx.begin("receiver 중단 유지 → 최대 시도 초과")
    sim = simulate(ctx, count=5)
    wait_for(ctx, "DEAD 5건", lambda c: c["DEAD"] >= 5, 90)
    summary = http("GET", GATEWAY_URL + "/ops/summary").data()
    ctx.check("운영 요약에 DEAD 표시", summary["counts"]["DEAD"] == 5, summary)
    ctx.begin("receiver 복구 후 운영자 수동 재처리")
    set_fault(ctx, "NONE")
    events = http("GET", GATEWAY_URL + "/ops/events?status=DEAD&limit=200").data()
    for ev in events:
        res = http("POST", "{}/ops/events/{}/requeue".format(GATEWAY_URL, ev["outboxId"]),
                   {"reason": "시뮬레이터 S10 수동 재처리 검증"}, headers={"X-Operator": "simulator"})
        ctx.log("  requeue outboxId={} → {}".format(ev["outboxId"], res.status))
    drain(ctx)
    rep = judge(ctx, "S10", expect_results=sim["expectedResults"])
    ctx.check("재처리 후 전 건 SENT", rep["sent"] == 5 and rep["dead"] == 0)


def s12(ctx):
    prepare(ctx, naive=True)
    try:
        sim, rep = _response_loss(ctx, "S12", naive=True)
        ctx.check("(대조군) 중복 검사가 없으면 품질 건이 중복 생성됨", rep["dup1"] > 0, "dup1={}".format(rep["dup1"]))
    finally:
        ctx.begin("receiver 기본 모드로 복귀")
        services.stop("receiver")
        services.start("receiver")


SCENARIOS = [
    {"id": "S1", "title": "단말 재전송", "fn": s1, "fault": "NONE",
     "desc": "결과의 30%를 같은 내용으로 1~2회 다시 보낸다.", "expect": "재전송은 200, 저장 건수 = 고유 결과 수"},
    {"id": "S2", "title": "같은 ID 다른 내용", "fn": s2, "fault": "NONE",
     "desc": "결과의 30%를 같은 키로 DTC만 바꿔 보낸다.", "expect": "409, 기존 결과 유지"},
    {"id": "S3", "title": "지연·순서 뒤바뀜", "fn": s3, "fault": "NONE",
     "desc": "이미 보낸 차량·ECU의 1~6시간 전 결과를 나중에 보낸다.", "expect": "최신 상태가 과거로 돌아가지 않음 (I6)"},
    {"id": "S4", "title": "수신 서버 중단", "fn": s4, "fault": "DOWN → NONE",
     "desc": "receiver가 503을 주는 동안 20건을 보내고 복구한다.", "expect": "자동 재시도로 전 건 SENT, DEAD 0"},
    {"id": "S5", "title": "응답 유실", "fn": s5, "fault": "DROP_AFTER_COMMIT 6s",
     "desc": "receiver가 커밋 후 응답을 6초 늦춰 gateway가 타임아웃(UNCERTAIN) 후 재전송한다.",
     "expect": "재전송 발생, 수신 측 중복 반영 0건"},
    {"id": "S7", "title": "처리 중 gateway 종료", "fn": s7, "fault": "SIGKILL",
     "desc": "receiver 응답을 기다리는 gateway 프로세스를 SIGKILL로 죽이고 다시 띄운다.",
     "expect": "lease 만료 후 ABANDONED·재선점, 중복 0건"},
    {"id": "S8", "title": "전송기 2개 동시 실행", "fn": s8, "fault": "gateway ×2",
     "desc": "gateway 프로세스 2개(gw-1, gw-2)가 같은 outbox를 동시에 처리한다.",
     "expect": "같은 건 동시 선점 없음, 대조 일치"},
    {"id": "S9", "title": "늦게 끝난 전송기", "fn": s9, "fault": "SIGSTOP / SIGCONT",
     "desc": "응답 대기 중인 gw-1을 멈추고 gw-2가 이어받은 뒤 gw-1을 재개한다.",
     "expect": "늦은 결과가 상태를 덮어쓰지 않음 (late_outcome), 중복 0건"},
    {"id": "S10", "title": "재시도 한도 초과", "fn": s10, "fault": "DOWN 유지",
     "desc": "최대 시도(6회) 후 DEAD가 되면 운영 API로 사유를 남겨 재처리한다.",
     "expect": "DEAD 5건 → 재처리 후 전 건 SENT"},
    {"id": "S12", "title": "중복 검사 없는 비교", "fn": s12, "fault": "naive + DROP_AFTER_COMMIT",
     "desc": "receiver를 중복 검사 없는 비교 모드로 띄우고 S5를 반복한다.",
     "expect": "(대조군) 품질 건 중복 발생 dup1 > 0"},
]
BY_ID = {s["id"]: s for s in SCENARIOS}


def catalog():
    return [{k: v for k, v in s.items() if k != "fn"} for s in SCENARIOS]


def run(scenario_id, ctx):
    """시나리오 실행. 반환값은 front·CLI가 쓰는 결과 dict."""
    scenario = BY_ID[scenario_id]
    started = time.time()
    error = None
    try:
        scenario["fn"](ctx)
    except Stopped:
        error = "사용자가 중단했습니다"
    except Exception as exc:  # noqa: BLE001 - 실행기 경계에서 모든 실패를 보고한다
        error = "{}: {}".format(type(exc).__name__, exc)
    if error:
        ctx.log("✘ " + error)
        try:
            fault.set_fault("NONE")
        except Exception:  # noqa: BLE001
            pass
    passed = error is None and bool(ctx.checks) and all(c["ok"] for c in ctx.checks)
    return {"scenario": scenario_id, "title": scenario["title"], "passed": passed, "error": error,
            "elapsedSec": round(time.time() - started, 1), "checks": ctx.checks,
            "simulation": ctx.simulation, "reconcile": ctx.reconcile}


def main(argv=None):
    p = argparse.ArgumentParser(prog="python3 -m scripts.scenarios", description="장애 시나리오 실행")
    p.add_argument("action", choices=["list", "run"])
    p.add_argument("ids", nargs="*", help="S1 S4 ... 또는 all")
    p.add_argument("--json", action="store_true")
    args = p.parse_args(argv)
    if args.action == "list":
        for s in SCENARIOS:
            print("{:<4} {:<18} {}".format(s["id"], s["title"], s["expect"]))
        return 0
    ids = [s["id"] for s in SCENARIOS] if args.ids in ([], ["all"]) else [i.upper() for i in args.ids]
    results = []
    for sid in ids:
        print("\n===== {} {} =====".format(sid, BY_ID[sid]["title"]))
        result = run(sid, Context(on_log=print))
        results.append(result)
        print("결과: {} ({}s)".format("PASS" if result["passed"] else "FAIL", result["elapsedSec"]))
    print("\n" + "\n".join("{:<4} {}".format(r["scenario"], "PASS" if r["passed"] else "FAIL") for r in results))
    if args.json:
        print(json.dumps(results, ensure_ascii=False, indent=2, default=str))
    return 0 if all(r["passed"] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
