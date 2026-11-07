# DL-10 stale 선점 결과 처리 구현 계획

> 문서 상태: 구현 계획 · 문서 정리: 2026-11-07  
> 원본: [계획 자료](../../../../agent/plan-agent/out/02-delivery-retry-design/dl-10-stale-claim/plan.md) · [전체 목차](README.md)  
> 아래 FACT와 BASELINE은 원본 계획 수립 시점의 기록입니다. READY는 계획 준비 상태이며 구현 완료나 테스트 통과를 뜻하지 않습니다.

PLAN_STATUS: READY — 0행 처리 규칙(02 §7, §8)과 `late_outcome` 컬럼·제약 확정
BLOCKER: NONE

근거: `temp/설계/02_전송_재처리_설계.md` §7, §8(S9), §10 D6 · 구현 순서: **11번째** (4일차) · 선행: DL-03, DL-07, DL-09, DL-11 · 후행: 04 운영 시도 이력 조회 · BASELINE: main @ 2125a48

## 1. 요약

lease가 지난 뒤 늦게 끝난 이전 선점자(토큰 T1)가 결과를 기록하려 하면 TX2가 0행입니다. 이미 새 선점자(T2)가 있거나 처리가 끝난 상태입니다.
이때 outbox 상태는 건드리지 않습니다. 대신 이미 `ABANDONED`가 된 T1 시도 행에 실제 결과를 `late_outcome`으로 남기고 `STALE_CLAIM` WARN을 남깁니다.
D6(T1 → 만료 → T2 재선점 → T1 기록 시도 → T2 기록)으로 "상태는 현재 선점자만 바꾼다"를 확인합니다.

## 2. 요구사항

| REQ | 요구 동작 | 수용 기준 |
|---|---|---|
| REQ-01 | D6 늦은 결과 | T1 선점 → 만료 → T2 재선점 → `record(T1, SUCCESS)` → 반환 `STALE`. outbox `PENDING`·`claim_token=T2`·`attempt_count=2` 그대로. T1 시도 `outcome='ABANDONED'`, `late_outcome='SUCCESS'`. T2 시도 `STARTED` 그대로. WARN `STALE_CLAIM outcome=SUCCESS lateRecorded=true` |
| REQ-02 | 이후 정상 기록 | 이어서 `record(T2, SUCCESS)` → `SENT` |
| REQ-03 | 이미 끝난 건에 늦은 결과 | T2가 `SENT`로 끝난 뒤 `record(T1, RETRYABLE)` → `STALE`, 행 `SENT` 유지, T1 `late_outcome='RETRYABLE'` |
| REQ-04 | 한 번만 기록 | 같은 T1으로 다시 `record` → `late_outcome` 첫 값 유지, `lateRecorded=false` |

## 3. 계획 수립 당시 구조 (FACT)

- `V2__outbox.sql` `delivery_attempt`
  - `late_outcome varchar(24)`
  - `ck_attempt_late_outcome`: `late_outcome is null or (outcome = 'ABANDONED' and late_outcome in ('SUCCESS','RETRYABLE','UNCERTAIN','NON_RETRYABLE'))`
  - DL-05 분류 결과 4종이 이 허용 값과 정확히 같습니다.
- 재선점 시 T1 시도는 TX1 ②에서 `ABANDONED`가 됩니다(DL-03/09). 그래서 0행이 나오는 시점의 T1 시도는 정상이라면 이미 `ABANDONED`입니다.
  - 경합 순서도 이 전제를 지킵니다. T2의 TX1 ①이 outbox 행을 잠근 채 ②를 실행하는 동안 T1의 TX2 UPDATE는 같은 행에서 대기합니다. T2가 커밋한 뒤 T1은 새 행 버전(토큰 T2)으로 조건을 다시 평가해 0행이 됩니다(READ COMMITTED). 이 시점에 ②는 이미 커밋돼 있으므로 `recordLateOutcome`이 `ABANDONED` 행을 찾습니다.
  - 반대로 T1의 TX2가 먼저 행을 잠그면 T2의 ①은 `SKIP LOCKED`로 그 행을 건너뜁니다.
- lease만 지나고 아직 재선점이 없으면 토큰이 그대로라 T1의 TX2는 정상 기록됩니다(stale 아님). 02 §8의 "정합성은 `claim_token`으로 지킨다"와 같습니다.
- DL-07 `DeliveryRecorder#record`: outbox UPDATE 0행이면 상태를 바꾸지 않고 `STALE_CLAIM` WARN 후 `STALE`을 반환합니다.

## 4. 변경 설계 (PROPOSED)

- `DeliveryAttemptMapper#recordLateOutcome(String claimToken, String lateOutcome)` → `int`
  ```sql
  UPDATE delivery_attempt SET late_outcome = #{lateOutcome}
   WHERE claim_token = #{claimToken}::uuid AND outcome = 'ABANDONED' AND late_outcome IS NULL
  ```
  - `outcome='ABANDONED'` 조건으로 check 제약 위반을 미리 피합니다. `late_outcome IS NULL`로 첫 결과만 남깁니다.
- `DeliveryRecorder#record`의 0행 분기: 같은 트랜잭션에서 `recordLateOutcome` 호출 → `log.warn("STALE_CLAIM outcome={} lateRecorded={}", …)` → `STALE` 반환. 0행이어도 예외로 바꾸지 않습니다. 상태 불변이 목적이고, 이력 보강은 부가 정보입니다.
- `DELIVERY_RESULT` 로그(DL-11)는 `next=STALE`로 남습니다.

## 5. 작업

| TASK | REQ | 대상 (경로+심볼) | 변경 내용 | 완료 조건 |
|---|---|---|---|---|
| TASK-01 | REQ-01, 03, 04 | `delivery/DeliveryAttemptMapper#recordLateOutcome` + XML | 4절 SQL | TEST-01, 03, 04 |
| TASK-02 | REQ-01~04 | `delivery/DeliveryRecorder#record` 0행 분기 | `late_outcome` 기록·로그 | TEST-01~04 |

순서: TASK-01 → TASK-02

## 6. 테스트

| TEST | REQ | 조건·입력 | 기대 결과 (DB·로그) | 종류 |
|---|---|---|---|---|
| TEST-01 | REQ-01 | D6: `claim(1)`→T1, `locked_until` 과거로, `claim(1)`→T2, `record(T1 이벤트, SUCCESS 200)` | REQ-01 (`OutputCaptureExtension`) | integrationTest (`OutboxRecordIT`) |
| TEST-02 | REQ-02 | TEST-01 이어서 `record(T2 이벤트, SUCCESS)` | `SENT`, T2 시도 `SUCCESS` | integrationTest |
| TEST-03 | REQ-03 | 새 건으로 구성: `claim`→T1, 만료, `claim`→T2, `record(T2, SUCCESS)`로 `SENT` 확정 → `record(T1, RETRYABLE)`. TEST-01의 T1은 이미 `late_outcome`이 있어 REQ-04 규칙상 재사용할 수 없음 | REQ-03 | integrationTest |
| TEST-04 | REQ-04 | TEST-01 직후 `record(T1, UNCERTAIN)` | `late_outcome='SUCCESS'` 유지, 로그 `lateRecorded=false` | integrationTest |

명령: `./gradlew :gateway:integrationTest`

검토: 0행 분기의 경합 순서(재선점 TX1 커밋 후 재평가)·lease만 만료된 경우, `ck_attempt_late_outcome` 허용 값과 DL-05 분류 결과 4종 일치, 무작위 토큰(DL-07 TEST-04)으로 0행일 때 `lateRecorded=false`, 같은 토큰 재기록, 테스트 데이터 재사용 충돌, 반영 2건(경합 순서 근거 명시, TEST-03 구성 수정)
