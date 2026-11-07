# DL-08 최대 시도 초과 처리 구현 계획

> 문서 상태: 구현 계획 · 문서 정리: 2026-11-07  
> 원본: [계획 자료](../../../../agent/plan-agent/out/02-delivery-retry-design/dl-08-max-attempts/plan.md) · [전체 목차](README.md)  
> 아래 FACT와 BASELINE은 원본 계획 수립 시점의 기록입니다. READY는 계획 준비 상태이며 구현 완료나 테스트 통과를 뜻하지 않습니다.

PLAN_STATUS: READY — 한도 판정 시점(02 §5.2)과 선점 시 초과 처리(02 §4 ③-b) 확정
BLOCKER: NONE

근거: `temp/설계/02_전송_재처리_설계.md` §4 ③-b, §5.2, §6 · 구현 순서: **8번째** · 선행: DL-02, DL-03, DL-07 · 후행: 04 수동 재처리(`cycle_no`) · BASELINE: main @ 2125a48

## 1. 요약

두 경로에서 `DEAD(MAX_ATTEMPTS)`를 만듭니다.
(1) TX2: `RETRYABLE`·`UNCERTAIN`인데 이번 시도가 한도(`attempt_count ≥ max-attempts`)에 닿았으면 재예약하지 않고 `DEAD`로 끝냅니다.
(2) TX1: 결과 기록 없이 종료가 반복돼 선점 직후 `attempt_count > max-attempts`가 된 건은 전송하지 않습니다. 대신 `DEAD` + `SKIPPED_MAX_ATTEMPTS` 시도 행을 남깁니다.
D7(`DOWN` 유지, 한도 3 → 3회 후 DEAD)로 확인합니다.

## 2. 요구사항

| REQ | 요구 동작 | 수용 기준 |
|---|---|---|
| REQ-01 | 한도 도달 실패 | `max-attempts=3`, 선점 후 `attempt_count=3`인 건에 `RETRYABLE` 기록 → `status='DEAD'`, `dead_reason='MAX_ATTEMPTS'`, `last_outcome='RETRYABLE'`, 시도 행 `RETRYABLE`. `UNCERTAIN`도 같음(`last_outcome='UNCERTAIN'`) |
| REQ-02 | 한도에서 재시도 불가 | `attempt_count=3`에 `NON_RETRYABLE` → `dead_reason='NON_RETRYABLE'` |
| REQ-03 | 선점 시 초과 | `attempt_count=3`(PENDING, 미선점)을 `claim` → 반환 목록에 없음, 전송 없음. 행: `DEAD`, `MAX_ATTEMPTS`, `last_outcome='SKIPPED_MAX_ATTEMPTS'`, 선점 해제. 시도 행: `attempt_no=4`, `SKIPPED_MAX_ATTEMPTS`, `started_at = finished_at`, 선점 토큰 동일 |
| REQ-04 | 이전 STARTED가 있는 초과 건 | 만료된 `STARTED` 시도가 남은 초과 건을 선점 → 시도 이력 `[…, ABANDONED, SKIPPED_MAX_ATTEMPTS]`, 고유 제약 위반 없음 |
| REQ-05 | D7 | 스텁 503 고정, `max-attempts=3`, `base=0s` → `runOnce` 3회 → `DEAD(MAX_ATTEMPTS)`, 시도 3행 `RETRYABLE`(`attempt_no` 1·2·3). 4번째 `runOnce` → 스텁 요청 수 3 유지 |

## 3. 계획 수립 당시 구조 (FACT)

- `V2__outbox.sql`
  - `ck_outbox_last_outcome`이 `SKIPPED_MAX_ATTEMPTS`를 허용합니다.
  - `ck_outbox_dead_reason in ('NON_RETRYABLE','MAX_ATTEMPTS')`.
  - `delivery_attempt`의 `ck_attempt_finished`: SKIPPED 행은 `finished_at`이 필수입니다.
  - 시도 행 고유 키: `uq_attempt_claim_token`, `uq_attempt_no`. ③-b 행은 ①에서 발급한 토큰과 늘어난 `attempt_count`를 그대로 쓰므로 충돌하지 않습니다(02 §4).
- DL-03 `OutboxClaimer#claim`: ① → ② → 행마다 ③-a. DL-07 `DeliveryRecorder#record`: 결과별 분기.
- `cycle_no`·`attempt_count` 초기화는 04 수동 재처리의 책임입니다. 이 기능은 현재 사이클의 `attempt_count`만 봅니다.

## 4. 변경 설계 (PROPOSED)

- `DeliveryRecorder#record` 분기 추가
  - `outcome ∈ {RETRYABLE, UNCERTAIN}` 이고 `e.attemptCount() >= props.maxAttempts()` → `markDead(deadReason="MAX_ATTEMPTS", outcome=원래 outcome)`, 반환 `DEAD(MAX_ATTEMPTS)`.
  - 02 §5.2의 "`attempt_count = max-attempts`이면"을 `>=`로 구현합니다. 실행 중 한도를 낮춘 경우에도 무한 재시도를 막습니다.
- `OutboxClaimer#claim` ③ 분기
  - `e.attemptCount() > props.maxAttempts()`:
    ```sql
    -- OutboxMapper#markSkippedDead (int)
    UPDATE outbox_event SET status = 'DEAD', dead_reason = 'MAX_ATTEMPTS', dead_at = now(),
           claim_token = NULL, locked_by = NULL, locked_until = NULL,
           last_outcome = 'SKIPPED_MAX_ATTEMPTS', updated_at = now()
     WHERE id = #{id} AND claim_token = #{claimToken}::uuid
    -- DeliveryAttemptMapper#insertSkipped
    INSERT INTO delivery_attempt (outbox_id, cycle_no, attempt_no, claim_token, worker_id, started_at, finished_at, outcome)
    VALUES (#{id}, #{cycleNo}, #{attemptNo}, #{claimToken}::uuid, #{workerId}, now(), now(), 'SKIPPED_MAX_ATTEMPTS')
    ```
    해당 건은 반환 목록에서 뺍니다. 같은 TX1 안이므로 방금 잡은 잠금을 그대로 쓰고, `markSkippedDead`는 항상 1행입니다.
  - 그 밖: 기존 ③-a.
  - ②(ABANDONED 정리)는 ③-b보다 먼저 실행됩니다(DL-03 순서 유지) → REQ-04.

## 5. 작업

| TASK | REQ | 대상 (경로+심볼) | 변경 내용 | 완료 조건 |
|---|---|---|---|---|
| TASK-01 | REQ-01, 02 | `delivery/DeliveryRecorder#record` | 한도 분기 | TEST-01, 02 |
| TASK-02 | REQ-03, 04 | `delivery/OutboxMapper#markSkippedDead`, `delivery/DeliveryAttemptMapper#insertSkipped` + XML, `delivery/OutboxClaimer#claim` | ③-b | TEST-03, 04 |
| TASK-03 | REQ-05 | `DeliveryFlowIT` | D7 추가 | TEST-05 |

순서: TASK-01 → TASK-02 → TASK-03

## 6. 테스트

`OutboxRecordIT`·`OutboxClaimIT`에 `gateway.delivery.max-attempts=3`을 지정합니다(두 클래스의 기존 테스트는 시도 1~2회라 영향 없음). 초과 상태는 `UPDATE outbox_event SET attempt_count = …`로 만듭니다.

| TEST | REQ | 조건·입력 | 기대 결과 (DB) | 종류 |
|---|---|---|---|---|
| TEST-01 | REQ-01 | `attempt_count=2` → `claim`(→3) → `record(RETRYABLE)`, 다른 건으로 `record(UNCERTAIN)` | REQ-01 | integrationTest (`OutboxRecordIT`) |
| TEST-02 | REQ-02 | `attempt_count=2` → `claim` → `record(NON_RETRYABLE)` | `dead_reason='NON_RETRYABLE'` | integrationTest |
| TEST-03 | REQ-03 | `attempt_count=3` → `claim(10)` | 반환 0, REQ-03 컬럼 | integrationTest (`OutboxClaimIT`) |
| TEST-04 | REQ-04 | `attempt_count=2` → `claim`(→3, STARTED) → `locked_until` 과거로 → `claim` | 반환 0, 시도 `[(3, ABANDONED), (4, SKIPPED_MAX_ATTEMPTS)]`, 행 `DEAD/MAX_ATTEMPTS` | integrationTest |
| TEST-05 | REQ-05 | D7 (DL-02 `DeliveryFlowIT` 설정: `max-attempts=3`, `base=0s`) | REQ-05 | integrationTest (`DeliveryFlowIT`) |

명령: `./gradlew :gateway:integrationTest`

검토: TX2 한도 분기(`>=`)와 D7 횟수, ③-b가 ①의 토큰·늘어난 `attempt_count`를 써서 `uq_attempt_claim_token`·`uq_attempt_no`와 충돌하지 않음, ②→③-b 순서와 `uq_attempt_outbox_started`, 실행 중 한도를 낮춘 경우(초과 행은 전송 없이 DEAD), 04 requeue 후(`attempt_count=0`, `cycle_no+1`) 재시작, DEAD 행에 `STARTED` 시도가 남지 않음(DEAD 전이는 모두 시도 종료와 같은 TX), 반영 0건
