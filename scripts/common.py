"""simulator·scripts·front가 함께 쓰는 설정과 HTTP 도우미.

표준 라이브러리만 사용한다. 주소·DB 접속 정보는 환경변수로 바꿀 수 있으며
기본값은 docs/getting-started.md의 로컬 실행 기본값과 같다.
"""
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUN_DIR = ROOT / "build" / "run"

GATEWAY_URL = os.environ.get("SIM_GATEWAY_URL", "http://localhost:8080")
RECEIVER_URL = os.environ.get("SIM_RECEIVER_URL", "http://localhost:8081")

_PG_HOST = os.environ.get("SIM_PG_HOST", "localhost")
_PG_PORT = os.environ.get("POSTGRES_PORT", "5432")
GATEWAY_DSN = os.environ.get(
    "SIM_GATEWAY_DSN",
    "host={} port={} dbname=gateway user=gateway password={}".format(
        _PG_HOST, _PG_PORT, os.environ.get("GATEWAY_DB_PASSWORD", "gateway-local")),
)
RECEIVER_DSN = os.environ.get(
    "SIM_RECEIVER_DSN",
    "host={} port={} dbname=receiver user=receiver password={}".format(
        _PG_HOST, _PG_PORT, os.environ.get("RECEIVER_DB_PASSWORD", "receiver-local")),
)


class HttpResult:
    def __init__(self, status, body, latency_ms):
        self.status = status
        self.body = body
        self.latency_ms = latency_ms

    def data(self):
        """ApiResponse 성공 응답의 data, 또는 ProblemDetail 본문."""
        if isinstance(self.body, dict) and "data" in self.body:
            return self.body["data"]
        return self.body


def http(method, url, body=None, headers=None, timeout=10.0):
    """JSON 요청을 보내고 HttpResult를 돌려준다. 연결 실패는 status=0으로 표현한다."""
    data = None
    hdrs = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        hdrs["Content-Type"] = "application/json"
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(url, data=data, method=method, headers=hdrs)
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            status = resp.status
    except urllib.error.HTTPError as err:
        raw = err.read()
        status = err.code
    except (urllib.error.URLError, OSError) as err:
        return HttpResult(0, {"error": str(getattr(err, "reason", err))}, _elapsed(started))
    try:
        parsed = json.loads(raw.decode("utf-8")) if raw else None
    except ValueError:
        parsed = raw.decode("utf-8", "replace")
    return HttpResult(status, parsed, _elapsed(started))


def _elapsed(started):
    return int((time.monotonic() - started) * 1000)


def healthy(base_url, timeout=1.5):
    res = http("GET", base_url + "/actuator/health", timeout=timeout)
    return res.status == 200


def connect(dsn):
    """psycopg 연결. 설치되지 않았으면 설치 방법을 담은 오류를 낸다."""
    try:
        import psycopg  # noqa: WPS433
    except ImportError as exc:
        raise RuntimeError(
            "psycopg가 필요합니다: python3 -m pip install -r scripts/requirements.txt") from exc
    return psycopg.connect(dsn, autocommit=True, connect_timeout=3)
