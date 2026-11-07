# DL-09 lease 만료 회수 구현 계획

> 문서 상태: 구현 계획 · 문서 정리: 2026-11-07  
> 원본: [계획 자료](../../../../agent/plan-agent/out/02-delivery-retry-design/dl-09-lease-reclaim/plan.md) · [전체 목차](README.md)  
> 아래 FACT와 BASELINE은 원본 계획 수립 시점의 기록입니다. READY는 계획 준비 상태이며 구현 완료나 테스트 통과를 뜻하지 않습니다.

PLAN_STATUS: READY — 회수 SQL(02 §4 ②)과 장애 지점별 회복(02 §8) 확정
BLOCKER: NONE

근거: `temp/설계/02_전송_재처리_설계.md` §4 ②, §8 · 구현 순서: **10번째** (4일차) · 선행: DL-02, DL-03(②), DL-07 · 후행: 04 시나리오 S7 · BASELINE: main @ 2125a48

## 1. 요약

전송 중 프로세스가 죽어 결과가 기록되지 않은 건은 lease(`locked_until`)가 지나면 다시 선점됩니다. 그때 이전 `STARTED` 시도를 `ABANDONED`로 정리합니다.
선점 조건(`locked_until < now()`)과 ② SQL은 DL-03에서 이미 TX1에 들어갑니다(DL-03 검토 반영). ②가 없으면 재선점 시 부분 고유 인덱스 위반이 나기 때문입니다. 이 기능은 코드 변경 없이 두 가지를 검증합니다. S7을 처음부터 끝까지 재현하는 D4, 그리고 아직 만료되지 않은 lease를 건드리지 않는지입니다.

## 2. 요구사항

| REQ | 요구 동작 | 수용 기준 |
|---|---|---|
| REQ-01 | D4 처리 중 종료 회복 | `claim`만 하고 전송하지 않음 → `locked_until`을 과거로 → `runOnce`(스텁 200) → 행 `SENT`, `attempt_count=2`, 시도 `[(1, ABANDONED, finished_at not null), (2, SUCCESS)]`, 스텁 수신 1건 |
| REQ-02 | 유효한 lease 보호 | `claim`만 하고 lease는 그대로 → `runOnce` → `claimed=0`, 스텁 수신 0, 시도 `(1, STARTED)` 유지 |
| REQ-03 | HTTP 후 TX2 전 종료 | `claim` → 스텁으로 직접 1회 전송(기록 안 함) → `locked_until` 과거로 → `runOnce` → `SENT`, 스텁 수신 2건(같은 `X-Event-Id`). receiver 쪽에서는 `DUPLICATE`로 처리될 상황입니다(02 §8 표 4행) |

## 3. 계획 수립 당시 구조 (FACT)

- `V2__outbox.sql`: `uq_attempt_outbox_started (outbox_id) where outcome = 'STARTED'`, `ck_attempt_finished`, `ck_attempt_late_outcome`(ABANDONED에만 `late_outcome` 허용 — DL-10에서 사용).
- 설정: `lease` 기본 30s (DL-01), lease > connect + read 검증.
- DL-03 이후: `OutboxClaimer#claim`의 ① 조건 `(locked_until IS NULL OR locked_until < now())`와 ② `DeliveryAttemptMapper#abandonStarted`. DL-03 TEST-04가 SQL 수준의 재선점을 이미 확인합니다.

## 4. 변경 설계 (PROPOSED)

- 운영 코드 변경은 없습니다. DL-03이 아래 조건을 이미 지키는지 테스트로 확인합니다.
  - ② `abandonStarted`는 ①에서 선점한 ID에 한정합니다. 다른 전송기가 아직 lease 안에서 진행 중인 시도는 건드리지 않습니다.
  - 순서: ① → ② → ③. ②가 ③보다 늦으면 부분 고유 인덱스 위반으로 TX1이 롤백됩니다.
- lease 연장(heartbeat)은 두지 않습니다(02 §8). 늦게 끝난 이전 선점자의 결과는 DL-10이 처리합니다.
- 테스트에서는 프로세스를 실제로 죽이지 않고 "선점만 하고 전송 생략 + `locked_until` 과거로"로 S7을 재현합니다(`docs/architecture/overview.md` 검증 방식 표).

## 5. 작업

| TASK | REQ | 대상 (경로+심볼) | 변경 내용 | 완료 조건 |
|---|---|---|---|---|
| TASK-01 | REQ-01~03 | `DeliveryFlowIT` | D4 및 보조 시나리오 | TEST-01~03 |

## 6. 테스트

| TEST | REQ | 조건·입력 | 기대 결과 (DB·스텁) | 종류 |
|---|---|---|---|---|
| TEST-01 | REQ-01 | D4: `createPending(1)` → `OutboxClaimer.claim(1)` → `UPDATE outbox_event SET locked_until = now() - interval '1 second'` → 스텁 200 → `runOnce` | REQ-01 | integrationTest (`DeliveryFlowIT`) |
| TEST-02 | REQ-02 | `claim(1)` → `runOnce` | REQ-02 | integrationTest |
| TEST-03 | REQ-03 | `claim(1)`로 받은 건을 `ReceiverClient.send`로 직접 보냄 → 만료 → `runOnce` | REQ-03 | integrationTest |

명령: `./gradlew :gateway:integrationTest`
