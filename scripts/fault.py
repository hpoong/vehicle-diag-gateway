"""receiver 장애 주입 (chaos 프로필에서만 동작).

    python3 -m scripts.fault DOWN
    python3 -m scripts.fault DROP_AFTER_COMMIT --hold-ms 6000
    python3 -m scripts.fault NONE
"""
import argparse
import json
import sys

from scripts.common import RECEIVER_URL, RUN_DIR, http

MODES = ("NONE", "DOWN", "DROP_AFTER_COMMIT")
_STATE_FILE = RUN_DIR / "fault.json"


def set_fault(mode, hold_ms=None, receiver_url=RECEIVER_URL):
    """장애 모드를 바꾸고 receiver가 확정한 설정을 돌려준다. receiver에는 조회 API가 없으므로 마지막 설정을 파일에 남긴다."""
    mode = mode.upper()
    if mode not in MODES:
        raise ValueError("mode must be one of {}".format(MODES))
    body = {"mode": mode}
    if hold_ms is not None:
        body["holdMs"] = int(hold_ms)
    res = http("POST", receiver_url.rstrip("/") + "/admin/faults", body, timeout=5)
    if res.status == 404:
        raise RuntimeError("receiver가 chaos 프로필로 실행되지 않았습니다 (/admin/faults 404)")
    if res.status != 200:
        raise RuntimeError("장애 설정 실패 status={} body={}".format(res.status, res.body))
    config = res.data()
    RUN_DIR.mkdir(parents=True, exist_ok=True)
    _STATE_FILE.write_text(json.dumps(config))
    return config


def last_fault():
    try:
        return json.loads(_STATE_FILE.read_text())
    except (OSError, ValueError):
        return None


def clear_last_fault():
    try:
        _STATE_FILE.unlink()
    except OSError:
        pass


def main(argv=None):
    p = argparse.ArgumentParser(prog="python3 -m scripts.fault", description="receiver 장애 주입")
    p.add_argument("mode", choices=MODES + tuple(m.lower() for m in MODES))
    p.add_argument("--hold-ms", type=int, default=None, help="DROP_AFTER_COMMIT 응답 지연(ms, 기본 15000)")
    p.add_argument("--receiver-url", default=RECEIVER_URL)
    args = p.parse_args(argv)
    print(json.dumps(set_fault(args.mode, args.hold_ms, args.receiver_url)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
