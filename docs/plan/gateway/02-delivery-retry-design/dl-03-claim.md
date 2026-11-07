# DL-03 선점 (TX1) 구현 계획

> 문서 상태: 구현 계획 · 문서 정리: 2026-11-07  
> 원본: [계획 자료](../../../../agent/plan-agent/out/02-delivery-retry-design/dl-03-claim/plan.md) · [전체 목차](README.md)  
> 아래 FACT와 BASELINE은 원본 계획 수립 시점의 기록입니다. READY는 계획 준비 상태이며 구현 완료나 테스트 통과를 뜻하지 않습니다.

PLAN_STATUS: READY — 선점 SQL(02 §4 ①·②·③-a) 확정. ②는 스키마 제약 때문에 이번 TX1에 포함(검토 반영)
BLOCKER: NONE

근거: `temp/설계/02_전송_재처리_설계.md` §4 ①·②·③-a, §2 · 구현 순서: **5번째** · 선행: DL-01(`DeliveryProperties`, `WorkerIdentity`), DL-05(`AttemptOutcome`) · 후행: DL-07(결과 기록), DL-08(③-b), DL-09(D4), DL-02 · BASELINE: main @ 2125a48

## 1. 요약

짧은 트랜잭션 하나에서 전송 대상 outbox 행을 `FOR UPDATE SKIP LOCKED`로 잠그고, 행마다 새 `claim_token`을 발급합니다. `attempt_count`를 먼저 1 늘리고 lease를 설정한 뒤 `delivery_attempt`에 `STARTED` 행을 남깁니다.
다른 전송기가 잠근 행은 기다리지 않고 건너뜁니다. 같은 행을 두 번 선점하지 않는 것(D5)이 핵심 검증입니다.
02 문서는 ②(이전 `STARTED` → `ABANDONED`)를 4일차 DL-09로 나눴지만, 이번 TX1에 함께 넣습니다(검토 반영).
- ①의 선점 조건에 `locked_until < now()`가 이미 있어 만료 행은 3일차부터 재선점됩니다.
- 이때 ②가 없으면 ③-a INSERT가 `uq_attempt_outbox_started`를 위반해 TX1 전체가 롤백됩니다.
- 그 행은 `next_attempt_at`이 가장 이르기 때문에 매 폴링마다 배치 맨 앞에 다시 잡힙니다. 결국 같은 배치의 다른 건까지 영구히 전송되지 않습니다.

DL-09는 D4 시나리오 검증만 맡습니다.

## 2. 요구사항

| REQ | 요구 동작 | 수용 기준 |
|---|---|---|
| REQ-01 | 대상 선점 | 선점 가능 행 6개, `claim(4)` → 4건 반환(`next_attempt_at, id` 순 앞쪽 4개). 각 행: `claim_token` 서로 다르고 not null, `locked_by = instanceId`, `locked_until ≈ now()+lease`, `attempt_count = 1`. 나머지 2행은 변화 없음 |
| REQ-02 | 시도 시작 기록 | 선점한 4건마다 `delivery_attempt` 1행: `outcome='STARTED'`, `attempt_no = attempt_count`, `cycle_no = outbox.cycle_no`, `claim_token` 일치, `worker_id = instanceId + ":" + 호출 스레드 이름`, `finished_at is null` |
| REQ-03 | 제외 조건 | `next_attempt_at > now()`, `locked_until > now()`, `status in ('SENT','DEAD')`인 행은 선점되지 않습니다 |
| REQ-04 | 동시 선점 (D5) | outbox 100건, 두 스레드가 `claim(4)`를 빈 결과가 나올 때까지 반복 → 반환 ID 합집합 100개·중복 0, 모든 행 `attempt_count = 1`, `delivery_attempt` 100행 |
| REQ-05 | 만료 lease 재선점 | 선점 후 `locked_until`을 과거로 → 다시 `claim` → 같은 행, 새 토큰, `attempt_count = 2`. 이전 시도 `ABANDONED`(`finished_at` not null), 새 시도 `STARTED attempt_no=2`. 고유 제약 위반 없음 |
| REQ-06 | 원자성 | ③ 시도 INSERT가 실패하면 예외가 전달되고 outbox 행은 선점 전 그대로(`claim_token is null`, `attempt_count` 불변) |

## 3. 계획 수립 당시 구조 (FACT)

- `V2__outbox.sql`
  - `ix_outbox_pending (next_attempt_at, id) where status = 'PENDING'` — ① 정렬과 같은 순서입니다.
  - `ck_outbox_claim`: 선점 컬럼 3개는 모두 null이거나, `status='PENDING'`이면서 모두 not null.
  - `delivery_attempt`: `uq_attempt_claim_token`, `uq_attempt_no (outbox_id, cycle_no, attempt_no)`, **`uq_attempt_outbox_started (outbox_id) where outcome = 'STARTED'`**, `ck_attempt_finished ((outcome='STARTED') = (finished_at is null))`.
- `delivery/OutboxMapper`(`insert`, `findByResultId`)와 `mapper/delivery/OutboxMapper.xml`
  - uuid는 문자열 + `::uuid`로 바인딩하고, `::text`로 읽습니다.
  - record 결과는 `<resultMap>`의 `<constructor>`로 매핑합니다.
- `RETURNING`을 쓰는 쓰기 SQL은 `<select ... affectData="true" flushCache="true">`로 매핑합니다 (`mapper/ingest/DiagnosticResultMapper.xml#insertResult`).
- 트랜잭션 패턴: 별도 빈 + `@Transactional(rollbackFor = Exception.class)` (`ingest/IngestWriter#save`).
- 테스트 데이터: `IngestService#ingest`로 결과+outbox를 만들 수 있습니다(`IngestFlowIT`의 요청 JSON 패턴).
- `OutboxMapper` 사용처: `IngestWriter`, `DiagnosticResultQueryService`, `IngestService`(간접), `IngestRollbackIT`·`IngestFlowIT`(`@MockitoBean`/주입). 메서드를 **추가**만 하므로 기존 사용처 영향은 없습니다.

## 4. 변경 설계 (PROPOSED)

- `delivery/ClaimedEvent` record: `long id, String eventId, String payload, int attemptCount, int cycleNo, String claimToken`.
- `OutboxMapper#claim(int limit, String instanceId, long leaseMillis)` → `List<ClaimedEvent>`
  ```sql
  <select id="claim" resultMap="claimed" affectData="true" flushCache="true">
  WITH picked AS (
      SELECT id FROM outbox_event
       WHERE status = 'PENDING'
         AND next_attempt_at <= now()
         AND (locked_until IS NULL OR locked_until < now())
       ORDER BY next_attempt_at, id
       LIMIT #{limit}
         FOR UPDATE SKIP LOCKED
  )
  UPDATE outbox_event o
     SET claim_token = gen_random_uuid(), locked_by = #{instanceId},
         locked_until = now() + #{leaseMillis} * INTERVAL '1 millisecond',
         attempt_count = o.attempt_count + 1, updated_at = now()
    FROM picked p
   WHERE o.id = p.id
  RETURNING o.id, o.event_id::text, o.payload::text, o.attempt_count, o.cycle_no, o.claim_token::text
  </select>
  ```
  - lease는 `Duration`을 그대로 쓰려고 밀리초 단위로 넘깁니다.
  - `RETURNING` 행 순서는 보장되지 않습니다. 반환 후 Java에서 `id` 순으로 정렬해 로그·테스트를 안정적으로 만듭니다.
- 신규 `delivery/DeliveryAttemptMapper` (`delivery_attempt` 전용) + `mapper/delivery/DeliveryAttemptMapper.xml`
  - `int abandonStarted(List<Long> outboxIds)` — ②
    `UPDATE delivery_attempt SET outcome = 'ABANDONED', finished_at = now() WHERE outcome = 'STARTED' AND outbox_id IN (<foreach>)`
  - `void insertStarted(long outboxId, int cycleNo, int attemptNo, String claimToken, String workerId)` — ③-a
    `INSERT … VALUES (…, #{claimToken}::uuid, #{workerId}, now(), 'STARTED')`
- 신규 `delivery/OutboxClaimer` (`@Component`)
  - `@Transactional(rollbackFor = Exception.class) List<ClaimedEvent> claim(int limit)`
    1. `limit < 1`이면 빈 목록.
    2. ① `claim(limit, identity.instanceId(), lease.toMillis())`. 비면 반환.
    3. ② `abandonStarted(ids)`. ③보다 **먼저** 실행해야 `uq_attempt_outbox_started`와 충돌하지 않습니다.
    4. 행마다 ③-a `insertStarted(…, identity.workerId(Thread.currentThread().getName()))`. 상한 초과 분기(③-b)는 DL-08에서 추가합니다.
    5. 선점 목록 반환.
  - `IngestWriter`와 같은 이유로 호출자(DL-02 `DeliveryService`)와 다른 빈입니다.
- 동시성: `SKIP LOCKED`로 잠긴 행을 건너뛰고, 커밋 뒤에는 `locked_until > now()` 조건으로 제외됩니다. 두 경로 모두에서 같은 행이 두 번 선점되지 않습니다.
- 테스트 지원 `testsupport/OutboxFixture` (integrationTest)
  - `List<Long> createPending(int n)`: `IngestService.ingest`로 결과를 만들고 outbox ID를 돌려줍니다(스키마 not null 컬럼을 테스트에 복제하지 않음).
  - `void reset()`: `TRUNCATE ops_action_log, delivery_attempt, outbox_event, vehicle_ecu_latest, ecu_observation, diagnostic_result RESTART IDENTITY CASCADE`. 같은 클래스의 앞선 테스트가 남긴 `PENDING` 행이 선점 대상에 섞이지 않게 합니다.

## 5. 작업

| TASK | REQ | 대상 (경로+심볼) | 변경 내용 | 완료 조건 |
|---|---|---|---|---|
| TASK-01 | REQ-01, 03 | `delivery/OutboxMapper#claim` + XML, 신규 `delivery/ClaimedEvent` | ① SQL, 생성자 `<resultMap>` | TEST-01, 02 |
| TASK-02 | REQ-02, 05 | 신규 `delivery/DeliveryAttemptMapper#abandonStarted`·`#insertStarted` + XML | ②, ③-a SQL | TEST-01, 04 |
| TASK-03 | REQ-01~06 | 신규 `delivery/OutboxClaimer#claim` | TX1 순서 ①→②→③ | TEST-01~06 |
| TASK-04 | 전체 | 신규 `testsupport/OutboxFixture` | 생성·초기화 도우미 | TEST-01~05 |

순서: TASK-04 → TASK-01 → TASK-02 → TASK-03

## 6. 테스트

| TEST | REQ | 조건·입력 | 기대 결과 (DB) | 종류 |
|---|---|---|---|---|
| TEST-01 | REQ-01, 02 | `createPending(6)` → `claim(4)` | 4건 반환(가장 작은 ID 4개), REQ-01·02의 컬럼 값, 나머지 2행 `claim_token is null` | integrationTest (`OutboxClaimIT`) |
| TEST-02 | REQ-03 | 4행을 각각 `next_attempt_at = now()+1h` / 선점 후 그대로 / `SENT`(`sent_at` 채움) / `DEAD`(`dead_reason`·`dead_at` 채움)로 만든 뒤 `claim(10)` | 반환 0건, 4행 `attempt_count` 변화 없음 | integrationTest |
| TEST-03 | REQ-04 | D5: `createPending(100)`, `CountDownLatch`로 두 스레드 동시 시작, 빈 결과까지 `claim(4)` 반복 | 합집합 100·교집합 0, `count(*) FILTER (WHERE attempt_count <> 1) = 0`, `delivery_attempt` 100행·`outbox_id` 100종 | integrationTest |
| TEST-04 | REQ-05 | `claim` → `UPDATE outbox_event SET locked_until = now() - interval '1 second'` → `claim` | 같은 ID, 토큰 다름, `attempt_count=2`. 시도 2행: `(1, ABANDONED, finished_at not null)`, `(2, STARTED)` | integrationTest |
| TEST-05 | REQ-06 | `@MockitoBean DeliveryAttemptMapper`의 `insertStarted`가 `RuntimeException` | 예외 전달, outbox `claim_token is null`·`attempt_count=0` | integrationTest (`OutboxClaimRollbackIT`, Mock 때문에 별도 컨텍스트) |
| TEST-06 | REQ-05 | ② 누락 회귀 방지: `createPending(2)` → `claim(1)`(가장 이른 행 A 선점) → A의 `locked_until`을 과거로 → `claim(2)` | 예외 없음, A(재선점, `attempt_count=2`)와 B(`attempt_count=1`) 2건 반환. A 이전 시도 `ABANDONED` | integrationTest |

명령: `./gradlew :gateway:integrationTest` (Docker 필요). DL-01 TASK-03으로 전송기가 꺼진 상태에서 실행합니다.

## 7. ISSUE

- ISSUE-02 / `worker_id`의 "스레드 이름"은 TX1을 실행하는 스레드입니다(스케줄러 스레드, 테스트에서는 호출 스레드). 실제로 HTTP를 보내는 전송 스레드는 선점 뒤에 정해지므로 TX1 시점에는 알 수 없습니다 / 비차단 / 전송 스레드 이름이 꼭 필요하면 TX2에서 별도 컬럼에 기록해야 하며 스키마 변경 대상

검토: `OutboxMapper` 사용처(`IngestWriter`·`DiagnosticResultQueryService`·`DiagnosticResultMapper#findByKey` 조인·`IngestRollbackIT` Mock — 메서드 추가만이라 영향 없음), 동시 선점(SKIP LOCKED + 커밋 후 `locked_until` 재평가), TX1 중간 실패 롤백, 만료 행 재선점 시 부분 고유 인덱스, 기존 행(`attempt_count=0`, 선점 컬럼 null)·04 requeue 후(`cycle_no+1`, `attempt_count=0`) 행과의 `uq_attempt_no` 공존, 반영 1건
