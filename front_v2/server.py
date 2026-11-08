"""부품 LOT 추적 데모 서버 (면접·발표용 단순 화면).

    python3 front_v2/server.py         # http://127.0.0.1:8071

기존 front(장애 실험 콘솔)와 별개로, "고장 난 차들의 공통 부품 LOT를 찾는다"는 이야기 하나만 보여 준다.
simulator.fleet로 차량군을 만들고 gateway 추적 API를 호출한다. 로컬 개발 전용이며 127.0.0.1에만 바인딩한다.
"""
import json
import os
import random
import sys
import threading
import traceback
import urllib.parse
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from scripts import dbreset, services  # noqa: E402
from scripts.common import GATEWAY_DSN, GATEWAY_URL, RECEIVER_DSN, connect, http  # noqa: E402
from simulator.fleet import STORED_KINDS, FleetParams, run_fleet  # noqa: E402
from simulator.payloads import SendItem, SendPlan  # noqa: E402
from simulator.sender import send_plan  # noqa: E402

STATIC = Path(__file__).resolve().parent / "static"
PORT = int(os.environ.get("SIM_FRONT_V2_PORT", "8071"))

ECU, DTC, LOTS = "TCM", "P0700", 6
LOT_NAMES = ["L-24{:02d}".format(i + 1) for i in range(LOTS)]
SEVERITY = {"low": 0.15, "mid": 0.4, "high": 0.7}   # 결함 LOT 장착 차량의 고장 확률
BASE_RATE = 0.02                                    # 정상 LOT 장착 차량의 고장 확률
RESEND_RATE = 0.3                                   # 같은 진단 결과를 다시 보내는 비율 (통신 재전송)
NOISE = {"unknown_lot_rate": 0.03, "replace_rate": 0.05, "no_history_rate": 0.01}

LOCK = threading.Lock()
STORY = {}  # 마지막 실행: fleet, window, expected, trace


class BadRequest(Exception):
    pass


class Busy(Exception):
    pass


def ready():
    s = services.status()
    on = lambda name: s[name]["state"] in ("up", "external")
    return {"postgres": s["postgres"]["state"], "gateway": s["gateway"]["state"], "receiver": s["receiver"]["state"],
            "ok": s["postgres"]["state"] == "up" and on("gateway"), "all": s["postgres"]["state"] == "up" and on("gateway") and on("receiver")}


def _rows(cur, sql):
    cur.execute(sql)
    cols = [c.name for c in cur.description]
    iso = lambda v: v.isoformat() if hasattr(v, "isoformat") else v
    return [{c: iso(v) for c, v in zip(cols, row)} for row in cur.fetchall()]


def db_snapshot():
    """두 DB에 실제로 쌓인 데이터: 표별 건수와 최근 행 몇 개."""
    with connect(GATEWAY_DSN) as g, g.cursor() as gc:
        gc.execute("""SELECT (SELECT count(*) FROM vehicle_part),
                             (SELECT count(DISTINCT lot_no) FROM vehicle_part WHERE lot_no IS NOT NULL),
                             (SELECT count(*) FROM diagnostic_result),
                             (SELECT count(DISTINCT vin) FROM diagnostic_result),
                             (SELECT count(*) FROM outbox_event WHERE status = 'PENDING'),
                             (SELECT count(*) FROM outbox_event WHERE status = 'SENT'),
                             (SELECT count(*) FROM outbox_event WHERE status = 'DEAD')""")
        parts, lots, results, vins, pending, sent, dead = gc.fetchone()
        part_rows = _rows(gc, """SELECT vin, lot_no, part_no, serial_no, installed_at, removed_at
                                   FROM vehicle_part ORDER BY id DESC LIMIT 8""")
        result_rows = _rows(gc, """SELECT r.vin, r.device_id, r.event_id, r.occurred_at, o.dtc_codes
                                     FROM diagnostic_result r LEFT JOIN ecu_observation o ON o.result_id = r.id AND o.ecu_id = 'TCM'
                                    ORDER BY r.id DESC LIMIT 8""")
    with connect(RECEIVER_DSN) as r, r.cursor() as rc:
        rc.execute("SELECT (SELECT count(*) FROM received_event), (SELECT count(*) FROM quality_case)")
        received, cases = rc.fetchone()
        case_rows = _rows(rc, """SELECT vin, ecu_id, dtc_code, occurred_at, created_at
                                   FROM quality_case ORDER BY id DESC LIMIT 8""")
    return {"gateway": {"parts": parts, "lots": lots, "results": results, "vehicles": vins,
                        "pending": pending, "sent": sent, "dead": dead},
            "receiver": {"received": received, "cases": cases},
            "samples": {"parts": part_rows, "results": result_rows, "cases": case_rows}}


def reset_db():
    counts = dbreset.reset()
    STORY.clear()
    return {"cleared": {k: v for k, v in counts.items() if v}}


def prepare():
    """꺼져 있는 서비스만 켠다. receiver는 추적에 필요 없지만 진단 결과 전송 로그가 쌓이지 않도록 함께 켠다."""
    s = services.status()
    done = []
    if s["postgres"]["state"] != "up":
        services.postgres("up")
        done.append("postgres")
    for name in ("receiver", "gateway"):
        if s[name]["state"] == "down":
            services.start(name)
            done.append(name)
    return {"started": done, **ready()}


def reverse(window):
    q = urllib.parse.urlencode({"ecuId": ECU, "dtcCode": DTC, "from": window["from"], "to": window["to"]})
    res = http("GET", GATEWAY_URL + "/api/v1/trace/lots?" + q)
    if res.status != 200:
        raise RuntimeError("역추적 실패 HTTP {}: {}".format(res.status, res.body))
    return res.data()


def stored_results():
    with connect(GATEWAY_DSN) as conn, conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM diagnostic_result")
        return cur.fetchone()[0]


def run_story(body):
    try:
        vehicles = int(body.get("vehicles", 300))
    except (TypeError, ValueError):
        raise BadRequest("차량 수가 올바르지 않습니다")
    if not 100 <= vehicles <= 2000:
        raise BadRequest("차량 수는 100~2000대입니다")
    severity = body.get("severity", "mid")
    if severity not in SEVERITY:
        raise BadRequest("결함 정도는 low|mid|high")
    defect = body.get("defectLot") or "random"
    if defect == "random":
        defect = random.choice(LOT_NAMES)
    elif defect not in LOT_NAMES:
        raise BadRequest("결함 LOT는 {} 중 하나입니다".format(", ".join(LOT_NAMES)))
    noise = bool(body.get("noise", True))
    reset = body.get("reset", True) is not False
    if not ready()["ok"]:
        raise BadRequest("DB 또는 gateway가 꺼져 있습니다. 위의 '서비스 켜기'를 먼저 누르세요")
    params = FleetParams(vehicles=vehicles, lots=LOTS, defect_lot=defect, defect_rate=SEVERITY[severity],
                         base_rate=BASE_RATE, duplicate_rate=RESEND_RATE, diag_per_vehicle=2,
                         **(NOISE if noise else {k: 0.0 for k in NOISE}))
    if reset:
        dbreset.reset()
    summary, fleet = run_fleet(params, return_fleet=True)
    prod, sim = summary["production"], summary.get("simulation")
    if not summary["ok"]:
        print("전송 실패 production={} simulation={}".format(prod, sim and sim["byKind"]), file=sys.stderr)
        step = "생산 이력" if not prod["ok"] else "진단 결과"
        raise RuntimeError("{} 전송이 실패했습니다. gateway 상태를 확인한 뒤 다시 시도하세요".format(step))
    trace = reverse(summary["window"])
    STORY.clear()
    STORY.update(fleet=fleet, window=summary["window"], expected=summary["expected"], trace=trace, defect=defect)
    by_kind = sim["byKind"]
    return {
        "setup": {"vehicles": vehicles, "lots": LOT_NAMES, "severity": severity, "noise": noise, "reset": reset,
                  "ecuId": ECU, "dtcCode": DTC},
        "received": {"partRows": prod["rows"], "partInserted": prod["inserted"],
                     "diagSent": sum(sum(v.values()) for v in by_kind.values()),
                     "diagResent": sum(by_kind.get("duplicate", {}).values()),
                     "diagStored": stored_results()},
        "trace": trace,
        "answer": {"defectLot": defect, "expected": summary["expected"]},
    }


def forward(lot):
    if not STORY:
        raise BadRequest("먼저 데이터를 보내세요")
    if lot not in LOT_NAMES:
        raise BadRequest("알 수 없는 LOT")
    q = urllib.parse.urlencode({"ecuId": ECU, "limit": 2000})
    res = http("GET", GATEWAY_URL + "/api/v1/trace/lots/{}/vehicles?{}".format(urllib.parse.quote(lot), q))
    if res.status != 200:
        raise RuntimeError("순추적 실패 HTTP {}: {}".format(res.status, res.body))
    data = res.data()
    return {"forward": data, "expected": STORY["expected"]["forward"].get(lot)}


def replay():
    """저장된 진단 결과를 전부 한 번 더 보낸다. 저장 건수와 추적 숫자가 그대로인지 본다."""
    if not STORY:
        raise BadRequest("먼저 데이터를 보내세요")
    fleet = STORY["fleet"]
    before = stored_results()
    plan = SendPlan(fleet.run_id, [SendItem(1, "duplicate", item.payload) for item in fleet.plan.items
                                   if item.kind in STORED_KINDS], fleet.plan.vehicles)
    report = send_plan(plan, concurrency=8).to_dict()
    after = stored_results()
    trace = reverse(STORY["window"])
    return {"resent": report["done"], "allOk": report["ok"], "before": before, "after": after,
            "traceUnchanged": trace == STORY["trace"], "trace": trace}


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(STATIC), **kwargs)

    def log_message(self, fmt, *args):
        if "/api/" not in (str(args[0]) if args else ""):
            super().log_message(fmt, *args)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def _json(self, status, body):
        raw = json.dumps(body, ensure_ascii=False, default=str).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        try:
            return json.loads(self.rfile.read(n).decode("utf-8")) if n else {}
        except ValueError:
            raise BadRequest("JSON 본문이 아닙니다")

    def do_GET(self):
        if self.path.startswith("/api/"):
            return self._api("GET")
        return super().do_GET()

    def do_POST(self):
        if self.path.startswith("/api/"):
            return self._api("POST")
        self._json(404, {"error": "not found"})

    def _api(self, method):
        url = urllib.parse.urlsplit(self.path)
        route = (method, url.path)
        query = dict(urllib.parse.parse_qsl(url.query))
        try:
            if route == ("GET", "/api/ready"):
                return self._json(200, ready())
            if route == ("GET", "/api/db"):
                return self._json(200, db_snapshot())
            if route == ("GET", "/api/forward"):
                return self._json(200, forward(query.get("lot", "")))
            handlers = {("POST", "/api/prepare"): lambda: prepare(),
                        ("POST", "/api/run"): lambda: run_story(self._body()),
                        ("POST", "/api/replay"): lambda: replay(),
                        ("POST", "/api/reset"): lambda: reset_db()}
            if route not in handlers:
                return self._json(404, {"error": "not found"})
            if not LOCK.acquire(blocking=False):
                raise Busy("다른 작업이 진행 중입니다")
            try:
                return self._json(200, handlers[route]())
            finally:
                LOCK.release()
        except BadRequest as exc:
            self._json(400, {"error": str(exc)})
        except Busy as exc:
            self._json(409, {"error": str(exc)})
        except Exception as exc:  # noqa: BLE001
            traceback.print_exc()
            self._json(500, {"error": str(exc) if isinstance(exc, RuntimeError) else "{}: {}".format(type(exc).__name__, exc)})


def main():
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    server.daemon_threads = True
    print("LOT 추적 데모 → http://127.0.0.1:{}".format(PORT))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
