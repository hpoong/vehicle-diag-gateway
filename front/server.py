"""시뮬레이터·장애 스크립트를 브라우저에서 다루는 로컬 콘솔 서버.

    python3 front/server.py            # http://127.0.0.1:8070

표준 라이브러리 HTTP 서버로 정적 파일(front/static)과 /api/*를 제공한다.
/api는 simulator·scripts 모듈을 그대로 호출하며, gateway 운영 API는 CORS 없이 쓰도록 중계한다.
로컬 개발 전용이다. 127.0.0.1에만 바인딩하며 인증이 없다.
"""
import json
import os
import sys
import threading
import time
import traceback
import urllib.parse
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from scripts import dbreset, fault, reconcile, scenarios, services  # noqa: E402
from scripts.common import GATEWAY_URL, http  # noqa: E402
from simulator.payloads import build_plan  # noqa: E402
from simulator.sender import send_plan  # noqa: E402

STATIC = Path(__file__).resolve().parent / "static"
PORT = int(os.environ.get("SIM_FRONT_PORT", "8070"))


class Job:
    """한 번에 하나만 실행하는 백그라운드 작업 (시나리오·전송·빌드·프로세스 제어)."""

    _seq = 0

    def __init__(self, kind, title):
        Job._seq += 1
        self.id = Job._seq
        self.kind = kind
        self.title = title
        self.state = "running"
        self.logs = []
        self.result = None
        self.error = None
        self.progress = None
        self.started = time.time()
        self.finished = None
        self.stop_event = threading.Event()

    def log(self, msg):
        self.logs.append("{} {}".format(time.strftime("%H:%M:%S"), msg))

    def view(self, since=0):
        return {"id": self.id, "kind": self.kind, "title": self.title, "state": self.state,
                "logs": self.logs[since:], "logCount": len(self.logs), "result": self.result,
                "error": self.error, "progress": self.progress,
                "elapsedSec": round((self.finished or time.time()) - self.started, 1)}


class Jobs:
    def __init__(self):
        self.current = None
        self.history = []
        self.lock = threading.Lock()

    def busy(self):
        return self.current is not None and self.current.state == "running"

    def start(self, kind, title, fn):
        with self.lock:
            if self.busy():
                raise Conflict("다른 작업이 실행 중입니다: " + self.current.title)
            job = Job(kind, title)
            self.current = job

        def runner():
            try:
                job.result = fn(job)
                job.state = "done"
                if isinstance(job.result, dict) and job.result.get("passed") is False:
                    job.state = "failed"
                    job.error = job.result.get("error")
            except Exception as exc:  # noqa: BLE001
                job.state = "failed"
                job.error = "{}: {}".format(type(exc).__name__, exc)
                job.log("✘ " + job.error)
            job.finished = time.time()
            self.history.insert(0, {k: v for k, v in job.view().items() if k != "logs"})
            del self.history[30:]

        threading.Thread(target=runner, daemon=True).start()
        return job


JOBS = Jobs()


class Conflict(Exception):
    pass


class BadRequest(Exception):
    pass


# ------------------------------------------------------------------ 작업 정의

def job_scenario(sid):
    if sid not in scenarios.BY_ID:
        raise BadRequest("unknown scenario " + sid)

    def fn(job):
        ctx = scenarios.Context(on_log=job.logs.append, stop_event=job.stop_event)
        result = scenarios.run(sid, ctx)
        job.log("결과: {} ({}s)".format("PASS" if result["passed"] else "FAIL", result["elapsedSec"]))
        return result
    return "scenario", "{} {}".format(sid, scenarios.BY_ID[sid]["title"]), fn


def job_scenarios_all(ids):
    def fn(job):
        results = []
        for sid in ids:
            if job.stop_event.is_set():
                break
            job.log("===== {} {} =====".format(sid, scenarios.BY_ID[sid]["title"]))
            ctx = scenarios.Context(on_log=job.logs.append, stop_event=job.stop_event)
            job.progress = {"done": len(results), "total": len(ids), "current": sid}
            r = scenarios.run(sid, ctx)
            results.append({k: r[k] for k in ("scenario", "title", "passed", "error", "elapsedSec", "checks")})
            job.log("결과: {} ({}s)".format("PASS" if r["passed"] else "FAIL", r["elapsedSec"]))
        job.progress = {"done": len(results), "total": len(ids)}
        return {"batch": results, "passed": len(results) == len(ids) and all(r["passed"] for r in results)}
    return "batch", "시나리오 {}개 연속 실행".format(len(ids)), fn


def job_simulate(p):
    def num(key, default, cast=float):
        value = p.get(key, default)
        return default if value in (None, "") else cast(value)

    kwargs = dict(count=num("count", 50, int), duplicate_rate=num("duplicateRate", 0), conflict_rate=num(
        "conflictRate", 0), late_rate=num("lateRate", 0), invalid_rate=num("invalidRate", 0),
        vehicles=num("vehicles", None, int), seed=num("seed", None, int))
    if not 1 <= kwargs["count"] <= 20000:
        raise BadRequest("count는 1~20000")

    def fn(job):
        plan = build_plan(**kwargs)
        job.log("run={} 계획={} 저장 기대 {}건".format(plan.run_id, plan.counts(), plan.expected_results))

        def progress(rep):
            job.progress = {"done": rep.done, "total": rep.total}

        report = send_plan(plan, gateway_url=p.get("gatewayUrl") or GATEWAY_URL,
                           concurrency=num("concurrency", 8, int), rate_per_sec=num("rate", 0),
                           on_progress=progress, stop_event=job.stop_event).to_dict()
        for kind, statuses in sorted(report["byKind"].items()):
            job.log("  {:<9} {}".format(kind, statuses))
        job.log("p95={}ms 처리량={}/s 기대와 다른 응답 {}건".format(
            report["latencyMs"]["p95"], report["throughputPerSec"], report["unexpectedCount"]))
        return {"simulation": report, "passed": report["ok"]}
    return "simulate", "시뮬레이터 {}건".format(kwargs["count"]), fn


def job_service(name, action, naive=False):
    if action == "build":
        def fn(job):
            services.build(on_line=job.log)
            return {"message": "bootJar 빌드 완료"}
        return "service", "bootJar 빌드", fn
    if name == "postgres":
        if action not in ("up", "down"):
            raise BadRequest("postgres action은 up|down")
        return "service", "postgres " + action, lambda job: {"message": services.postgres(action)}
    if name not in services.SERVICES or action not in services.ACTIONS:
        raise BadRequest("unknown service/action")

    def fn(job):
        if action == "start":
            msg = services.start(name, naive=naive)
        else:
            msg = services.ACTIONS[action](name)
        job.log("{} {}: {}".format(name, action, msg))
        if action in ("stop", "kill") and name == "receiver":
            fault.clear_last_fault()
        return {"message": msg}
    return "service", "{} {}".format(name, action), fn


# ------------------------------------------------------------------ HTTP

def live_counts():
    try:
        c = scenarios.counts()
        c["ok"] = True
        return c
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": str(exc)}


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(STATIC), **kwargs)

    def log_message(self, fmt, *args):  # 폴링 요청 로그 생략
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
        if not n:
            return {}
        try:
            return json.loads(self.rfile.read(n).decode("utf-8"))
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
        parts = [p for p in url.path.split("/") if p][1:]
        query = dict(urllib.parse.parse_qsl(url.query))
        try:
            status, body = self._route(method, parts, query)
        except BadRequest as exc:
            status, body = 400, {"error": str(exc)}
        except Conflict as exc:
            status, body = 409, {"error": str(exc)}
        except Exception as exc:  # noqa: BLE001
            traceback.print_exc()
            status, body = 500, {"error": "{}: {}".format(type(exc).__name__, exc)}
        self._json(status, body)

    def _route(self, method, parts, query):
        key = (method, "/".join(parts[:2]))
        if key == ("GET", "state"):
            job = JOBS.current
            return 200, {"services": services.status(), "fault": fault.last_fault(),
                         "job": job.view(len(job.logs)) if job else None, "canPause": services.CAN_PAUSE}
        if key == ("GET", "counts"):
            return 200, live_counts()
        if key == ("GET", "scenarios"):
            return 200, scenarios.catalog()
        if key == ("GET", "history"):
            return 200, JOBS.history
        if key == ("GET", "jobs/current"):
            job = JOBS.current
            return 200, job.view(int(query.get("since", 0))) if job else None
        if key == ("POST", "jobs/stop"):
            if JOBS.busy():
                JOBS.current.stop_event.set()
                JOBS.current.log("중단 요청")
            return 200, {"stopping": JOBS.busy()}
        if key == ("POST", "jobs"):
            body = self._body()
            kind = body.get("type")
            if kind == "scenario":
                ids = body.get("ids") or [body.get("id")]
                spec = job_scenario(ids[0]) if len(ids) == 1 else job_scenarios_all(
                    [i for i in ids if i in scenarios.BY_ID])
            elif kind == "simulate":
                spec = job_simulate(body.get("params") or {})
            elif kind == "service":
                spec = job_service(body.get("name"), body.get("action"), bool(body.get("naive")))
            else:
                raise BadRequest("type은 scenario|simulate|service")
            return 202, JOBS.start(*spec).view()
        if key == ("POST", "fault"):
            body = self._body()
            return 200, fault.set_fault(body.get("mode", "NONE"), body.get("holdMs"))
        if key == ("POST", "reset"):
            if JOBS.busy():
                raise Conflict("작업 실행 중에는 초기화할 수 없습니다")
            return 200, dbreset.reset()
        if key == ("GET", "reconcile"):
            rep = reconcile.reconcile(mode=query.get("mode", "standard"),
                                      allow_dead=query.get("allowDead") == "true",
                                      expect_results=int(query["expect"]) if query.get("expect") else None)
            return 200, rep
        if key == ("GET", "logs") and len(parts) == 2:
            if parts[1] not in services.SERVICES:
                raise BadRequest("unknown service")
            return 200, {"name": parts[1], "text": services.tail(parts[1], int(query.get("lines", 120)))}
        if parts and parts[0] == "ops":
            return self._ops(method, parts[1:], query)
        return 404, {"error": "not found"}

    def _ops(self, method, rest, query):
        """gateway 운영 API 중계. 응답 상태와 본문을 그대로 돌려준다."""
        base = query.pop("gateway", None) or GATEWAY_URL
        path = "/ops/" + "/".join(urllib.parse.quote(p) for p in rest)
        if query:
            path += "?" + urllib.parse.urlencode(query)
        if method == "POST":
            body = self._body()
            operator = body.pop("operator", None) or "front"
            res = http("POST", base + path, body, headers={"X-Operator": operator})
        else:
            res = http("GET", base + path)
        if res.status == 0:
            return 502, {"error": "gateway 연결 실패: {}".format(res.body.get("error"))}
        return res.status, res.body


def main():
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    server.daemon_threads = True
    print("Diag Gateway Lab → http://127.0.0.1:{}".format(PORT))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
