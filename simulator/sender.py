"""전송 계획을 gateway 수신 API로 보내고 응답을 집계한다."""
import threading
import time
from concurrent.futures import ThreadPoolExecutor

from scripts.common import GATEWAY_URL, http

INGEST_PATH = "/api/v1/diagnostic-results"


class SendReport:
    def __init__(self, plan):
        self.run_id = plan.run_id
        self.planned = plan.counts()
        self.expected_results = plan.expected_results
        self.total = len(plan.items)
        self.done = 0
        self.by_kind = {}          # kind -> {status: count}
        self.unexpected = []       # 기대와 다른 응답 (최대 20건 보관)
        self.unexpected_count = 0
        self.latencies = []
        self.started = time.time()
        self.finished = None
        self.stopped = False
        self._lock = threading.Lock()

    def record(self, item, res):
        with self._lock:
            self.done += 1
            bucket = self.by_kind.setdefault(item.kind, {})
            key = str(res.status)
            bucket[key] = bucket.get(key, 0) + 1
            self.latencies.append(res.latency_ms)
            if res.status != item.expected_status:
                self.unexpected_count += 1
                if len(self.unexpected) < 20:
                    self.unexpected.append({
                        "kind": item.kind, "expected": item.expected_status, "status": res.status,
                        "eventId": item.payload["eventId"], "body": res.body})

    def to_dict(self):
        lat = sorted(self.latencies)

        def pct(p):
            return lat[min(len(lat) - 1, int(len(lat) * p))] if lat else None

        elapsed = (self.finished or time.time()) - self.started
        return {
            "runId": self.run_id,
            "planned": self.planned,
            "expectedResults": self.expected_results,
            "total": self.total,
            "done": self.done,
            "byKind": self.by_kind,
            "unexpectedCount": self.unexpected_count,
            "unexpected": self.unexpected,
            "latencyMs": {"p50": pct(0.5), "p95": pct(0.95), "max": lat[-1] if lat else None},
            "elapsedSec": round(elapsed, 2),
            "throughputPerSec": round(self.done / elapsed, 1) if elapsed > 0 else None,
            "stopped": self.stopped,
            "ok": self.unexpected_count == 0 and not self.stopped and self.done == self.total,
        }


def send_plan(plan, gateway_url=GATEWAY_URL, concurrency=8, rate_per_sec=0.0,
              on_progress=None, stop_event=None):
    """단계별로 전송한다. rate_per_sec>0이면 전체 전송 속도를 제한한다."""
    report = SendReport(plan)
    url = gateway_url.rstrip("/") + INGEST_PATH
    interval = 1.0 / rate_per_sec if rate_per_sec and rate_per_sec > 0 else 0.0
    pace_lock = threading.Lock()
    next_slot = [time.monotonic()]

    def pace():
        if not interval:
            return
        with pace_lock:
            wait = next_slot[0] - time.monotonic()
            next_slot[0] = max(next_slot[0], time.monotonic()) + interval
        if wait > 0:
            time.sleep(wait)

    def send(item):
        if stop_event is not None and stop_event.is_set():
            return
        pace()
        res = http("POST", url, item.payload, headers={"X-Request-Id": item.payload["eventId"][:64]})
        report.record(item, res)
        if on_progress:
            on_progress(report)

    with ThreadPoolExecutor(max_workers=max(1, concurrency)) as pool:
        for phase_items in plan.phases():
            list(pool.map(send, phase_items))
            if stop_event is not None and stop_event.is_set():
                report.stopped = True
                break
    report.finished = time.time()
    return report
