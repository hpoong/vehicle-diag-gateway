"""가상 진단 단말의 결과 생성기.

전송 계획(SendPlan)은 세 단계로 나뉜다. 같은 단계 안에서는 순서를 섞고 동시에 보내며,
단계 사이에는 앞 단계가 모두 끝난 뒤 넘어간다. 그래서 기대 응답 코드가 실행마다 같다.

1. original  : 고유 결과 (기대 202) + 정책 위반 결과 (기대 422)
2. resend    : original과 같은 키·같은 내용 재전송 (기대 200), 같은 키·다른 내용 (기대 409)
3. late      : 이미 보낸 차량·ECU의 더 과거 진단 결과 (기대 202, 최신 상태는 바뀌지 않아야 함)
"""
import copy
import random
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))
VIN_CHARS = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789"
BRANDS = ["BRAND_A", "BRAND_B", "BRAND_C"]
ECUS = [
    ("ENGINE", ["E-1.0.3", "E-1.1.0"]),
    ("TCM", ["T-2.4.0", "T-2.5.1"]),
    ("ABS", ["A-0.9.8"]),
    ("BCM", ["B-3.0.0", "B-3.0.2"]),
    ("AIRBAG", ["SRS-1.2"]),
]
DTCS = ["P0301", "P0420", "P0171", "P0700", "C0035", "C0040", "B1342", "B0100", "U0100", "U0121"]


class SendItem:
    """전송 1건. kind는 기대 응답을 결정한다."""

    EXPECTED = {"original": 202, "invalid": 422, "duplicate": 200, "conflict": 409, "late": 202}

    def __init__(self, phase, kind, payload):
        self.phase = phase
        self.kind = kind
        self.payload = payload

    @property
    def expected_status(self):
        return self.EXPECTED[self.kind]


class SendPlan:
    def __init__(self, run_id, items, vehicles):
        self.run_id = run_id
        self.items = items
        self.vehicles = vehicles

    def phases(self):
        out = {}
        for item in self.items:
            out.setdefault(item.phase, []).append(item)
        return [out[p] for p in sorted(out)]

    @property
    def expected_results(self):
        """gateway에 새로 저장돼야 하는 결과 수 (= receiver에 반영돼야 하는 이벤트 수)."""
        return sum(1 for i in self.items if i.kind in ("original", "late"))

    def counts(self):
        out = {}
        for item in self.items:
            out[item.kind] = out.get(item.kind, 0) + 1
        return out


def _vin(rng):
    return "".join(rng.choice(VIN_CHARS) for _ in range(17))


def _iso(dt):
    return dt.astimezone(KST).isoformat(timespec="milliseconds")


def _ecus(rng, brand, ecu_ids):
    ecus = []
    for ecu_id in ecu_ids:
        versions = dict(ECUS)[ecu_id]
        n_dtc = rng.choice([0, 1, 1, 2, 3])
        ecu = {"ecuId": ecu_id, "dtcCodes": rng.sample(DTCS, n_dtc)}
        # BRAND_A는 swVersion 필수, 그 외 브랜드는 70%만 보낸다.
        if brand == "BRAND_A" or rng.random() < 0.7:
            ecu["swVersion"] = rng.choice(versions)
        ecus.append(ecu)
    return ecus


def build_plan(count=50, duplicate_rate=0.0, conflict_rate=0.0, late_rate=0.0,
               invalid_rate=0.0, vehicles=None, devices=5, seed=None, now=None):
    """전송 계획을 만든다. 비율은 0~1이며 count(고유 결과 수)를 기준으로 계산한다."""
    if count < 1:
        raise ValueError("count must be >= 1")
    rng = random.Random(seed)
    now = now or datetime.now(timezone.utc)
    run_id = "r" + now.astimezone(KST).strftime("%m%d%H%M%S") + "{:03d}".format(rng.randrange(1000))
    vehicles = vehicles or max(1, count // 4)
    fleet = []
    for _ in range(vehicles):
        fleet.append({"vin": _vin(rng), "brand": rng.choice(BRANDS)})

    seq = [0]

    def next_ids():
        seq[0] += 1
        return "SIM-DEV-{:03d}".format(rng.randrange(devices)), "{}-{:05d}".format(run_id, seq[0])

    def result(vehicle, occurred, ecu_ids=None):
        device_id, event_id = next_ids()
        ecu_ids = ecu_ids or rng.sample([e for e, _ in ECUS], rng.randint(1, 3))
        return {
            "deviceId": device_id,
            "eventId": event_id,
            "brand": vehicle["brand"],
            "vin": vehicle["vin"],
            "sessionId": "SES-" + event_id,
            "occurredAt": _iso(occurred),
            "ecus": _ecus(rng, vehicle["brand"], ecu_ids),
        }

    items = []
    originals = []
    for _ in range(count):
        vehicle = rng.choice(fleet)
        occurred = now - timedelta(seconds=rng.randint(0, 600))
        payload = result(vehicle, occurred)
        originals.append(payload)
        items.append(SendItem(1, "original", payload))

    for _ in range(int(round(count * invalid_rate))):
        vehicle = {"vin": _vin(rng), "brand": "BRAND_A"}
        payload = result(vehicle, now - timedelta(seconds=30))
        for ecu in payload["ecus"]:
            ecu.pop("swVersion", None)
        items.append(SendItem(1, "invalid", payload))

    for payload in rng.sample(originals, int(round(count * duplicate_rate))):
        for _ in range(rng.randint(1, 2)):
            items.append(SendItem(2, "duplicate", copy.deepcopy(payload)))

    for payload in rng.sample(originals, int(round(count * conflict_rate))):
        changed = copy.deepcopy(payload)
        first = changed["ecus"][0]
        extra = [d for d in DTCS if d not in first["dtcCodes"]]
        first["dtcCodes"] = first["dtcCodes"] + [rng.choice(extra)]
        items.append(SendItem(2, "conflict", changed))

    for base in rng.sample(originals, int(round(count * late_rate))):
        vehicle = {"vin": base["vin"], "brand": base["brand"]}
        older = datetime.fromisoformat(base["occurredAt"]) - timedelta(hours=rng.randint(1, 6))
        ecu_ids = [e["ecuId"] for e in base["ecus"]]
        items.append(SendItem(3, "late", result(vehicle, older, ecu_ids)))

    for phase in (1, 2, 3):
        chunk = [i for i in items if i.phase == phase]
        rng.shuffle(chunk)
        items = [i for i in items if i.phase != phase] + chunk
    return SendPlan(run_id, items, vehicles)
