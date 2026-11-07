"""시나리오 사이에 로컬 gateway·receiver DB의 업무 데이터를 비운다 (스키마는 유지).

로컬 Compose DB 전용이다. 실행 중인 gateway 전송기는 멈출 필요가 없다.

    python3 -m scripts.dbreset --yes
"""
import argparse
import sys

from scripts.common import GATEWAY_DSN, RECEIVER_DSN, connect

GATEWAY_TABLES = ("ops_action_log", "delivery_attempt", "outbox_event", "vehicle_ecu_latest",
                  "ecu_observation", "diagnostic_result")
RECEIVER_TABLES = ("quality_case", "quality_case_naive", "received_event")


def reset():
    counts = {}
    for dsn, tables in ((GATEWAY_DSN, GATEWAY_TABLES), (RECEIVER_DSN, RECEIVER_TABLES)):
        with connect(dsn) as conn, conn.cursor() as cur:
            for table in tables:
                cur.execute("SELECT count(*) FROM {}".format(table))
                counts[table] = cur.fetchone()[0]
            cur.execute("TRUNCATE {} RESTART IDENTITY".format(", ".join(tables)))
    return counts


def main(argv=None):
    p = argparse.ArgumentParser(prog="python3 -m scripts.dbreset", description="로컬 DB 업무 데이터 초기화")
    p.add_argument("--yes", action="store_true", help="확인 없이 실행")
    args = p.parse_args(argv)
    if not args.yes:
        answer = input("로컬 gateway·receiver DB의 데이터를 모두 지웁니다. 계속할까요? [y/N] ")
        if answer.strip().lower() != "y":
            return 1
    for table, n in reset().items():
        print("  truncated {:<20} ({} rows)".format(table, n))
    return 0


if __name__ == "__main__":
    sys.exit(main())
