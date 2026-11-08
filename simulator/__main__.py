"""가상 진단 단말 CLI.

    python3 -m simulator --count 100 --duplicate-rate 0.2 --late-rate 0.1
"""
import argparse
import json
import sys

from scripts.common import GATEWAY_URL
from simulator.payloads import build_plan
from simulator.sender import send_plan


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if argv[:1] == ["fleet"]:
        from simulator.fleet import fleet_main
        return fleet_main(argv[1:])
    p = argparse.ArgumentParser(prog="python3 -m simulator", description="가상 진단 단말: gateway로 진단 결과 전송")
    p.add_argument("--count", type=int, default=50, help="고유 진단 결과 수 (기본 50)")
    p.add_argument("--duplicate-rate", type=float, default=0.0, help="같은 내용 재전송 비율 → 200 (S1)")
    p.add_argument("--conflict-rate", type=float, default=0.0, help="같은 키·다른 내용 비율 → 409 (S2)")
    p.add_argument("--late-rate", type=float, default=0.0, help="과거 시각 결과를 늦게 보내는 비율 (S3)")
    p.add_argument("--invalid-rate", type=float, default=0.0, help="BRAND_A swVersion 누락 비율 → 422")
    p.add_argument("--vehicles", type=int, default=None, help="차량 수 (기본 count/4)")
    p.add_argument("--devices", type=int, default=5, help="단말 수")
    p.add_argument("--concurrency", type=int, default=8, help="동시 전송 수")
    p.add_argument("--rate", type=float, default=0.0, help="초당 전송 수 제한 (0=무제한)")
    p.add_argument("--seed", type=int, default=None, help="재현용 난수 seed")
    p.add_argument("--gateway-url", default=GATEWAY_URL)
    p.add_argument("--dry-run", action="store_true", help="전송하지 않고 첫 결과와 계획만 출력")
    p.add_argument("--json", action="store_true", help="결과를 JSON으로 출력")
    args = p.parse_args(argv)

    plan = build_plan(count=args.count, duplicate_rate=args.duplicate_rate, conflict_rate=args.conflict_rate,
                      late_rate=args.late_rate, invalid_rate=args.invalid_rate, vehicles=args.vehicles,
                      devices=args.devices, seed=args.seed)
    if args.dry_run:
        print(json.dumps({"runId": plan.run_id, "planned": plan.counts(),
                          "expectedResults": plan.expected_results,
                          "sample": plan.items[0].payload}, ensure_ascii=False, indent=2))
        return 0

    def progress(rep):
        if not args.json and (rep.done % 50 == 0 or rep.done == rep.total):
            print("  sent {}/{}".format(rep.done, rep.total), file=sys.stderr)

    report = send_plan(plan, gateway_url=args.gateway_url, concurrency=args.concurrency,
                       rate_per_sec=args.rate, on_progress=progress).to_dict()
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print("run={} planned={} expectedResults={}".format(report["runId"], report["planned"],
                                                           report["expectedResults"]))
        for kind, statuses in sorted(report["byKind"].items()):
            print("  {:<9} {}".format(kind, statuses))
        print("latency={} elapsed={}s throughput={}/s unexpected={}".format(
            report["latencyMs"], report["elapsedSec"], report["throughputPerSec"], report["unexpectedCount"]))
        for u in report["unexpected"][:5]:
            print("  ! {kind} expected={expected} got={status} eventId={eventId}".format(**u))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
