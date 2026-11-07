# DL-07 결과 기록 (TX2) 구현 계획

> 문서 상태: 구현 계획 · 문서 정리: 2026-11-07  
> 원본: [계획 자료](../../../../agent/plan-agent/out/02-delivery-retry-design/dl-07-record-result/plan.md) · [전체 목차](README.md)  
> 아래 FACT와 BASELINE은 원본 계획 수립 시점의 기록입니다. READY는 계획 준비 상태이며 구현 완료나 테스트 통과를 뜻하지 않습니다.

PLAN_STATUS: READY — 상태별 UPDATE와 토큰 조건(02 §7) 확정
BLOCKER: NONE

근거: `temp/설계/02_전송_재처리_설계.md` §7, §5.2 · 구현 순서: **6번째** · 선행: DL-03(`ClaimedEvent`, `DeliveryAttemptMapper`), DL-05(`DeliveryOutcome`), DL-06(`BackoffPolicy`) · 후행: DL-02, DL-08(한도), DL-10(`late_outcome`) · BASELINE: main @ 2125a48

## 1. 요약

분류 결과를 짧은 트랜잭션 하나로 기록합니다. outbox 상태는 `SENT`, 백오프 후 `PENDING`, `DEAD(NON_RETRYABLE)` 중 하나로 바뀌고, 같은 트랜잭션에서 시도 행에 결과를 적습니다.
모든 UPDATE는 `id`·`claim_token`·`status='PENDING'`이 일치할 때만 적용됩니다. 0행이면 다른 전송기가 이미 재선점한 것이므로 상태를 건드리지 않고 `STALE_CLAIM` WARN만 남깁니다. `late_outcome` 기록은 DL-10에서 추가합니다.

## 2. 요구사항

| REQ | 요구 동작 | 수용 기준 |
|---|---|---|
| REQ-01 | SUCCESS | `status='SENT'`, `sent_at` not null, `claim_token·locked_by·locked_until` null, `last_outcome='SUCCESS'`, `last_http_status=200`, `last_error` null. 시도 행 `SUCCESS`, `finished_at`·`http_status=200`·`latency_ms` 기록 |
| REQ-02 | RETRYABLE / UNCERTAIN (한도 미만) | `status='PENDING'`, `next_attempt_at ∈ [now()+delay 최소, now()+delay 최대]`, 선점 해제, `last_outcome`·`last_http_status`·`last_error` 기록. 시도 행 outcome·`error_code` 기록 |
| REQ-03 | NON_RETRYABLE | `status='DEAD'`, `dead_reason='NON_RETRYABLE'`, `dead_at` not null, 선점 해제, `last_outcome='NON_RETRYABLE'` |
| REQ-04 | 현재 선점자만 기록 | 다른 토큰으로 `record` → 반환 `STALE`, outbox 행·시도 행 모두 변화 없음, `STALE_CLAIM` WARN 1줄 |
| REQ-05 | 원자성 | outbox UPDATE와 시도 UPDATE가 한 트랜잭션. 시도 UPDATE가 0행이면 예외로 롤백하고 outbox도 바뀌지 않음 |

## 3. 계획 수립 당시 구조 (FACT)

- `V2__outbox.sql`
  - `ck_outbox_sent ((status='SENT') = (sent_at is not null))`
  - `ck_outbox_dead`: DEAD일 때만 `dead_reason`·`dead_at`
  - `ck_outbox_claim`: 선점 컬럼 3개를 함께 null로 만들어야 합니다.
  - `ck_outbox_dead_reason in ('NON_RETRYABLE','MAX_ATTEMPTS')`
- `delivery_attempt`: `ck_attempt_finished` — STARTED가 아닌 결과에는 `finished_at`이 필수입니다. `latency_ms >= 0`.
- DL-03 이후 `OutboxClaimer`가 TX1, `DeliveryAttemptMapper#insertStarted`가 시도 행을 만듭니다.
- 트랜잭션 패턴: 별도 빈 + `@Transactional(rollbackFor = Exception.class)` (`ingest/IngestWriter`).

## 4. 변경 설계 (PROPOSED)

- `OutboxMapper` 추가 (모두 `int` 반환, 공통 WHERE: `id = #{id} AND claim_token = #{claimToken}::uuid AND status = 'PENDING'`)
  ```sql
  -- markSent
  UPDATE outbox_event SET status = 'SENT', sent_at = now(),
         claim_token = NULL, locked_by = NULL, locked_until = NULL,
         last_outcome = 'SUCCESS', last_http_status = #{httpStatus}, last_error = NULL, updated_at = now()
   WHERE <공통>
  -- reschedule
  UPDATE outbox_event SET next_attempt_at = now() + #{delayMillis} * INTERVAL '1 millisecond',
         claim_token = NULL, locked_by = NULL, locked_until = NULL,
         last_outcome = #{outcome}, last_http_status = #{httpStatus}, last_error = #{error}, updated_at = now()
   WHERE <공통>
  -- markDead
  UPDATE outbox_event SET status = 'DEAD', dead_reason = #{deadReason}, dead_at = now(),
         claim_token = NULL, locked_by = NULL, locked_until = NULL,
         last_outcome = #{outcome}, last_http_status = #{httpStatus}, last_error = #{error}, updated_at = now()
   WHERE <공통>
  ```
  - `next_attempt_at`은 DB `now()` 기준입니다. 선점 쿼리(`next_attempt_at <= now()`)와 같은 시계를 씁니다.
- `DeliveryAttemptMapper#finish(String claimToken, String outcome, Integer httpStatus, String errorCode, String errorMessage, long latencyMs)` → `int`
  `UPDATE delivery_attempt SET outcome = #{outcome}, finished_at = now(), http_status = …, error_code = …, error_message = …, latency_ms = … WHERE claim_token = #{claimToken}::uuid AND outcome = 'STARTED'`
- 신규 `delivery/DeliveryRecorder` (`@Component`)
  - `@Transactional(rollbackFor = Exception.class) RecordResult record(ClaimedEvent e, DeliveryOutcome o)`
    | `o.outcome()` | outbox | 반환 |
    |---|---|---|
    | SUCCESS | `markSent` | `SENT` |
    | RETRYABLE, UNCERTAIN | `reschedule(delay = backoff.delay(e.attemptCount()))` | `RETRY(delay)` |
    | NON_RETRYABLE | `markDead("NON_RETRYABLE")` | `DEAD(NON_RETRYABLE)` |
    - outbox UPDATE가 1행이면 `finish(...)`. `finish`가 0행이면 `IllegalStateException`을 던져 전체를 롤백합니다. 행은 선점된 채 남고 lease가 만료되면 재선점됩니다. 시도 행이 STARTED로 남은 채 outbox만 끝나는 불일치를 막기 위해서입니다.
    - outbox UPDATE가 0행이면 아무것도 바꾸지 않고 `log.warn("STALE_CLAIM outcome={}", …)` 후 `STALE`을 반환합니다(DL-10에서 `late_outcome` 추가).
  - `delivery/RecordResult` record: `State state (SENT | RETRY | DEAD | STALE)`, `Duration nextDelay`, `String deadReason`.
- TX2가 DB 오류로 실패해도 여기서 재시도하지 않습니다(02 §7, §11). 예외는 호출자(DL-02)가 로그로 남기고 포기합니다.

## 5. 작업

| TASK | REQ | 대상 (경로+심볼) | 변경 내용 | 완료 조건 |
|---|---|---|---|---|
| TASK-01 | REQ-01~03 | `delivery/OutboxMapper#markSent`·`#reschedule`·`#markDead` + XML | 4절 SQL | TEST-01~03 |
| TASK-02 | REQ-01~03, 05 | `delivery/DeliveryAttemptMapper#finish` + XML | 시도 결과 기록 | TEST-01~03, 05 |
| TASK-03 | REQ-01~05 | 신규 `delivery/DeliveryRecorder#record`, `delivery/RecordResult` | 분기·토큰 조건·롤백 | TEST-01~05 |

순서: TASK-01 → TASK-02 → TASK-03

## 6. 테스트

공통: `OutboxFixture.createPending(1)` → `OutboxClaimer.claim(1)`로 받은 `ClaimedEvent`에 대해 `record`를 호출합니다.

| TEST | REQ | 조건·입력 | 기대 결과 (DB) | 종류 |
|---|---|---|---|---|
| TEST-01 | REQ-01 | `SUCCESS`, 200, latency 12 | REQ-01 값, 시도 `SUCCESS/200/12` | integrationTest (`OutboxRecordIT`) |
| TEST-02 | REQ-02 | `RETRYABLE HTTP_503`(기본 base 5s, attempt 1) / `UNCERTAIN READ_TIMEOUT` | `next_attempt_at - now()`가 2.5~5초(여유 1초), 선점 해제, `last_outcome` 각각 RETRYABLE / UNCERTAIN, 시도 `error_code` 각각 `HTTP_503` / `READ_TIMEOUT`(`http_status` null) | integrationTest |
| TEST-03 | REQ-03 | `NON_RETRYABLE HTTP_409` | `DEAD`, `dead_reason='NON_RETRYABLE'`, 시도 `NON_RETRYABLE` | integrationTest |
| TEST-04 | REQ-04 | `ClaimedEvent`의 토큰만 랜덤 UUID로 바꿔 `record(SUCCESS)` → 이어서 원래 토큰으로 `record(SUCCESS)` | 첫 호출 `STALE`, 행 그대로(`PENDING`, 원래 토큰, 시도 `STARTED`), 로그 `STALE_CLAIM` / 두 번째 `SENT` | integrationTest (`OutputCaptureExtension`) |
| TEST-05 | REQ-05 | `@MockitoBean DeliveryAttemptMapper`: `insertStarted`는 실제처럼 통과(무동작), `finish`가 0 반환 | `IllegalStateException`, outbox는 `PENDING`·토큰 유지(`markSent` 롤백) | integrationTest (`OutboxRecordRollbackIT`) |

명령: `./gradlew :gateway:integrationTest`

검토: TX2 UPDATE 3종의 토큰·상태 조건과 V2 check 제약(`ck_outbox_sent`·`ck_outbox_dead`·`ck_outbox_claim`·`ck_outbox_last_outcome`·`ck_attempt_finished`) 일치, 재선점과의 경합(행 잠금 대기 후 재평가로 0행), lease만 만료되고 재선점 없는 경우(정상 기록), TX2 중간 실패·DB 오류 시 남는 상태(선점 유지 → lease 만료 후 재선점), 연결 실패 시 `last_http_status`·`http_status` null 허용, `DiagnosticResultQueryService`·`findByKey`가 읽는 `status`·`attempt_count`·`last_outcome`·`sent_at`과 기록 값 연결, 반영 0건
