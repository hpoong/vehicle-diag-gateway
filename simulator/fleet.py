"""LOT 결함 차량군 생성과 전송 데이터만 이용한 독립 추적 기대값 계산."""
import argparse
import copy
import json
import math
import random
import sys
from collections import defaultdict
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone

from scripts.common import GATEWAY_URL
from simulator.payloads import BRANDS, DTCS, ECUS, KST, SendItem, SendPlan, _ecus, _iso, _vin
from simulator.sender import send_parts, send_plan

PART_BATCH_MAX = 1000
DIAG_SPAN = timedelta(days=14)
STORED_KINDS = ("original", "late")


@dataclass
class FleetParams:
    vehicles: int = 300
    lots: int = 6
    defect_lot: str = "L-2403"
    defect_rate: float = 0.4
    base_rate: float = 0.02
    unknown_lot_rate: float = 0.03
    replace_rate: float = 0.05
    no_history_rate: float = 0.01
    diag_per_vehicle: int = 2
    duplicate_rate: float = 0.0
    seed: int = None

    def validate(self):
        for name, lo, hi in (("vehicles", 1, 5000), ("lots", 1, 20), ("diag_per_vehicle", 1, 10)):
            value = getattr(self, name)
            if not isinstance(value, int) or not lo <= value <= hi:
                raise ValueError("{}는 {}~{} 정수여야 합니다".format(name, lo, hi))
        for name in ("defect_rate", "base_rate", "unknown_lot_rate", "replace_rate", "no_history_rate", "duplicate_rate"):
            if not 0 <= getattr(self, name) <= 1:
                raise ValueError("{}는 0~1이어야 합니다".format(name))
        if self.defect_lot not in ["L-24{:02d}".format(i + 1) for i in range(self.lots)]:
            raise ValueError("defect_lot은 생성 LOT 중 하나여야 합니다")


@dataclass
class Fleet:
    run_id: str
    window: dict
    parts: list
    batches: list
    plan: SendPlan


def _millis(value):
    return value.replace(microsecond=value.microsecond // 1000 * 1000)


def _utc(value):
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _parse(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def _split_batches(parts):
    groups = {}
    for row in parts:
        groups.setdefault(row["vin"], []).append(row)
    batches, batch = [], []
    for group in groups.values():
        if len(group) > PART_BATCH_MAX:
            raise ValueError("한 차량의 이력이 배치 상한을 넘습니다")
        if batch and len(batch) + len(group) > PART_BATCH_MAX:
            batches.append(batch)
            batch = []
        batch.extend(group)
    if batch:
        batches.append(batch)
    return batches


def build_fleet(params, now=None):
    params.validate()
    rng = random.Random(params.seed)
    now = _millis(now or datetime.now(timezone.utc))
    run_id = "f" + now.astimezone(KST).strftime("%m%d%H%M%S%f") + str(rng.randrange(100, 1000))
    vins, seen = [], set()
    while len(vins) < params.vehicles:
        vin = _vin(rng)
        if vin not in seen:
            vins.append(vin)
            seen.add(vin)
    lots = ["L-24{:02d}".format(i + 1) for i in range(params.lots)]
    assigned = [lots[i % len(lots)] for i in range(params.vehicles)]
    rng.shuffle(assigned)
    parts, originals = [], []
    serial = 0
    for vin, lot in zip(vins, assigned):
        no_history = rng.random() < params.no_history_rate
        replaced = rng.random() < params.replace_rate
        times = sorted(_millis(now - DIAG_SPAN + timedelta(seconds=rng.uniform(0, DIAG_SPAN.total_seconds() - 60)))
                       for _ in range(params.diag_per_vehicle))
        installed = _millis(now - timedelta(days=rng.uniform(60, 120)))
        actual = [(lot, installed, None)]
        if replaced:
            start, end = (times[0], times[-1]) if len(times) > 1 else (now - DIAG_SPAN, now - timedelta(seconds=60))
            replacement = _millis(start + (end - start) * rng.random())
            new_lot = rng.choice([other for other in lots if other != lot] or [lot])
            actual = [(lot, installed, replacement), (new_lot, replacement, None)]
        if not no_history:
            for actual_lot, start, end in actual:
                serial += 1
                parts.append({"vin": vin, "ecuId": "TCM", "partNo": "TCM-95440",
                              "serialNo": "S-{}-{:05d}".format(run_id, serial),
                              "lotNo": None if rng.random() < params.unknown_lot_rate else actual_lot,
                              "installedAt": _iso(start), "removedAt": None if end is None else _iso(end)})
        brand = rng.choice(BRANDS)
        for t in times:
            actual_lot = next(value for value, start, end in actual if start <= t and (end is None or t < end))
            faulty = rng.random() < (params.defect_rate if actual_lot == params.defect_lot else params.base_rate)
            codes = rng.sample([code for code in DTCS if code != "P0700"], rng.randrange(2))
            if faulty:
                codes.append("P0700")
            ecu = {"ecuId": "TCM", "swVersion": rng.choice(dict(ECUS)["TCM"]), "dtcCodes": codes}
            others = rng.sample([e for e, _ in ECUS if e != "TCM"], rng.randrange(3))
            event_id = "{}-{:05d}".format(run_id, len(originals) + 1)
            payload = {"deviceId": "SIM-DEV-{:03d}".format(rng.randrange(1, 6)), "eventId": event_id,
                       "brand": brand, "vin": vin, "sessionId": "SES-" + event_id,
                       "occurredAt": _iso(t), "ecus": [ecu] + _ecus(rng, brand, others)}
            originals.append(SendItem(1, "original", payload))
    duplicates = []
    for item in originals:
        if rng.random() < params.duplicate_rate:
            duplicates.extend(SendItem(2, "duplicate", copy.deepcopy(item.payload)) for _ in range(rng.randint(1, 2)))
    rng.shuffle(originals)
    rng.shuffle(duplicates)
    window = {"from": (now - DIAG_SPAN - timedelta(days=1)).replace(microsecond=0),
              "to": datetime.fromtimestamp(math.ceil(now.timestamp()), timezone.utc)}
    return Fleet(run_id, window, parts, _split_batches(parts), SendPlan(run_id, originals + duplicates, params.vehicles))


def expected_trace(parts, items, window, ecu_id="TCM", dtc="P0700"):
    """실제로 보낼 이력과 저장될 payload만 읽는다. 생성기의 실제 LOT 정보는 사용하지 않는다."""
    start, end = (window[k] if isinstance(window[k], datetime) else _parse(window[k]) for k in ("from", "to"))
    histories, installed, current, removed = defaultdict(list), defaultdict(set), defaultdict(set), defaultdict(set)
    for row in parts:
        if row["ecuId"] != ecu_id:
            continue
        histories[row["vin"]].append((row, _parse(row["installedAt"]), None if row["removedAt"] is None else _parse(row["removedAt"])))
        lot = row["lotNo"]
        if lot is not None:
            installed[lot].add(row["vin"])
            (current if row["removedAt"] is None else removed)[lot].add(row["vin"])
    observations = defaultdict(list)
    stored_keys = set()
    for item in items:
        if item.kind not in STORED_KINDS:
            continue
        payload = item.payload
        key = (payload["deviceId"], payload["eventId"])
        if key in stored_keys:
            continue
        stored_keys.add(key)
        t = _parse(payload["occurredAt"])
        if start <= t < end:
            for ecu in payload["ecus"]:
                if ecu["ecuId"] == ecu_id:
                    observations[payload["vin"]].append((t, dtc in ecu["dtcCodes"]))
    lots, attribution = {}, []
    zero = lambda: {"faultVehicles": 0, "diagnosedVehicles": 0}
    unattributed = {name: zero() for name in ("unknownLot", "noHistory", "ambiguous")}
    totals = zero()
    for vin, obs in sorted(observations.items()):
        first_fault = min((t for t, faulty in obs if faulty), default=None)
        last = max(t for t, _ in obs)
        ref = first_fault or last
        matches = [row for row, lo, hi in histories[vin] if lo <= ref and (hi is None or ref < hi)]
        lot = None
        if not matches:
            bucket = "noHistory"
        elif len(matches) > 1:
            bucket = "ambiguous"
        elif matches[0]["lotNo"] is None:
            bucket = "unknownLot"
        else:
            bucket, lot = "LOT", matches[0]["lotNo"]
        target = lots.setdefault(lot, zero()) if lot is not None else unattributed[bucket]
        for count in (target, totals):
            count["diagnosedVehicles"] += 1
            count["faultVehicles"] += int(first_fault is not None)
        attribution.append({"vin": vin, "firstFaultAt": None if first_fault is None else _utc(first_fault),
                            "lastDiagAt": _utc(last), "bucket": bucket, "lotNo": lot})
    result = {"ecuId": ecu_id, "dtcCode": dtc, "totals": totals,
              "lots": [dict(lotNo=lot, installedVehicles=len(installed[lot]), **counts) for lot, counts in sorted(lots.items())],
              "unattributed": unattributed,
              "forward": {lot: {"currentVehicles": len(current[lot]), "excludedRemoved": len(removed[lot] - current[lot])}
                          for lot in sorted(installed)}}
    if len(observations) <= 20:
        result["attribution"] = attribution
    return result


def _summary(params, fleet):
    return {"runId": fleet.run_id, "params": asdict(params), "window": {k: _utc(v) for k, v in fleet.window.items()},
            "expected": expected_trace(fleet.parts, fleet.plan.items, fleet.window)}


def run_fleet(params, gateway_url=GATEWAY_URL, concurrency=8, rate=0, on_log=None, on_progress=None,
              stop_event=None, return_fleet=False):
    fleet = build_fleet(params)
    summary = _summary(params, fleet)
    summary["production"] = send_parts(fleet.batches, gateway_url=gateway_url, source="OEM-SIM",
                                       run_id=fleet.run_id, on_log=on_log, stop_event=stop_event)
    summary["ok"] = False
    if summary["production"]["ok"]:
        summary["simulation"] = send_plan(fleet.plan, gateway_url=gateway_url, concurrency=concurrency,
                                            rate_per_sec=rate, on_progress=on_progress, stop_event=stop_event).to_dict()
        summary["ok"] = summary["simulation"]["ok"]
    return (summary, fleet) if return_fleet else summary


def fleet_main(argv=None):
    parser = argparse.ArgumentParser(prog="python3 -m simulator fleet", description="LOT 결함 차량군 생성·전송")
    for name, default in asdict(FleetParams()).items():
        parser.add_argument("--" + name.replace("_", "-"), default=default,
                            type=int if name in ("vehicles", "lots", "diag_per_vehicle", "seed") else str if name == "defect_lot" else float)
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--rate", type=float, default=0)
    parser.add_argument("--gateway-url", default=GATEWAY_URL)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    params = FleetParams(**{name: getattr(args, name) for name in asdict(FleetParams())})
    try:
        params.validate()
        if args.concurrency < 1 or not math.isfinite(args.rate) or args.rate < 0:
            raise ValueError("concurrency는 양수, rate는 0 이상이어야 합니다")
    except ValueError as exc:
        parser.error(str(exc))
    if args.dry_run:
        fleet = build_fleet(params)
        summary = _summary(params, fleet)
        positions = defaultdict(set)
        for index, batch in enumerate(fleet.batches):
            for row in batch:
                positions[row["vin"]].add(index)
        summary["production"] = {"rows": len(fleet.parts), "batches": len(fleet.batches),
                                 "batchSizes": list(map(len, fleet.batches)), "splitPairs": sum(len(p) > 1 for p in positions.values())}
        summary["simulation"] = {"planned": fleet.plan.counts(), "expectedResults": fleet.plan.expected_results}
    else:
        summary = run_fleet(params, gateway_url=args.gateway_url, concurrency=args.concurrency, rate=args.rate)
    if args.json or args.dry_run:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
    else:
        print("run={} vehicles={} production={}".format(summary["runId"], params.vehicles, summary["production"]))
        print("simulation={}".format(summary.get("simulation", {}).get("byKind", {})))
        print("expected={}".format(summary["expected"]))
        print("window from={from} to={to}".format(**summary["window"]))
    return 0 if args.dry_run or summary["ok"] else 1
