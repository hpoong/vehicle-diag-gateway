# DL-11 전송 로그 구현 계획

> 문서 상태: 구현 계획 · 문서 정리: 2026-11-07  
> 원본: [계획 자료](../../../../agent/plan-agent/out/02-delivery-retry-design/dl-11-delivery-log/plan.md) · [전체 목차](README.md)  
> 아래 FACT와 BASELINE은 원본 계획 수립 시점의 기록입니다. READY는 계획 준비 상태이며 구현 완료나 테스트 통과를 뜻하지 않습니다.

PLAN_STATUS: READY — MDC 키·로그 키워드(02 §9, 컨벤션 5.2) 확정. `next=` 표기만 제안
BLOCKER: NONE

근거: `temp/설계/02_전송_재처리_설계.md` §9 · `docs/convention/conventions.md` §5 · 구현 순서: **9번째** · 선행: DL-02, DL-03, DL-07, DL-08 · 후행: DL-10(`STALE_CLAIM`) · BASELINE: main @ 2125a48

## 1. 요약

전송 스레드에서 건마다 MDC(`outboxId`, `eventId`, `claimToken`, `attemptNo`)를 설정하고 작업이 끝나면 기존 MDC를 복원합니다.
시도마다 `DELIVERY_RESULT` 한 줄을 남기고, 상태 전이가 있으면 `OUTBOX_SENT`·`OUTBOX_DEAD reason=…`을 INFO로 남깁니다.
`logback-spring.xml`의 로그 패턴에 전송 MDC 키를 포함합니다. payload 원문은 남기지 않습니다.

## 2. 요구사항

| REQ | 요구 동작 | 수용 기준 |
|---|---|---|
| REQ-01 | 시도 결과 로그 | 기록(TX2)마다 INFO 1줄: `DELIVERY_RESULT outcome=RETRYABLE http=503 latencyMs=<n> next=PENDING+<ms>ms` / `next=SENT` / `next=DEAD`. `http`는 응답이 없으면 `-` |
| REQ-02 | 상태 전이 로그 | `SENT` 전이 시 `OUTBOX_SENT attempts=<n>`. `DEAD` 전이 시 `OUTBOX_DEAD reason=NON_RETRYABLE|MAX_ATTEMPTS`. TX1의 ③-b도 `OUTBOX_DEAD reason=MAX_ATTEMPTS outcome=SKIPPED_MAX_ATTEMPTS` |
| REQ-03 | MDC 설정·정리 | 위 로그 줄에 `outboxId:<id> eventId:<uuid> claimToken:<uuid> attemptNo:<n>`이 찍힙니다. 작업이 끝난 뒤 같은 `delivery-` 스레드에서 MDC에 네 키가 없습니다 |
| REQ-04 | payload 미기록 | D1 실행 로그 전체에 payload의 `vin` 값이 없습니다 |

## 3. 계획 수립 당시 구조 (FACT)

- 원본 계획은 `application.yml`의 `logging.pattern.level` 확장을 전제로 했습니다. 현재 출력 설정은 `gateway/src/main/resources/logback-spring.xml`의 `LOG_LEVEL_PATTERN`으로 분리되어 있으며, 수신 4개와 전송 3개 추가 키를 합한 MDC 필드 7개를 포함합니다. YAML에는 출력 패턴을 중복 정의하지 않습니다.
- MDC 사용: `web-support` `RequestIdFilter`(`requestId`, 요청 후 복원). `IngestService`는 `resultId`를 넣습니다.
- 컨벤션 5.2: `eventId`는 "수신 서비스, 전송 워커"가 같이 쓰는 키, `claimToken`은 "Outbox 워커". 상태 전이는 INFO(5.1).
- 로그 검증 패턴: `IngestFlowIT`의 `@ExtendWith(OutputCaptureExtension.class)`.

## 4. 변경 설계 (PROPOSED)

- `delivery/DeliveryMdc` — `static Scope open(ClaimedEvent e)`가 기존 MDC 전체를 저장한 뒤 네 키를 넣고, `Scope implements AutoCloseable#close()`가 이전 MDC를 복원합니다. 기존 MDC가 없었다면 전체를 비웁니다. `DeliveryService#deliver`에서 `try (var ignored = DeliveryMdc.open(e)) { … }`로 감쌉니다. 전송 스레드는 재사용되므로 작업 종료와 예외 발생 시 모두 복원해야 합니다.
- `OutboxClaimer`의 ③-b 로그도 같은 `DeliveryMdc.open`으로 감쌉니다(TX1 스레드에서 해당 건만).
- 로그 위치
  - `DELIVERY_RESULT`: `DeliveryService#deliver`에서 `RecordResult`를 받은 직후. `next`는 `RecordResult.state`로 정합니다. RETRY면 `PENDING+<nextDelay.toMillis()>ms`, STALE이면 `STALE`.
  - `OUTBOX_SENT`·`OUTBOX_DEAD`: `DeliveryRecorder`에서 UPDATE가 1행일 때만 남깁니다(커밋 전이지만 실패하면 `DELIVERY_RECORD_FAILED`가 이어서 남음).
- `logback-spring.xml`의 `LOG_LEVEL_PATTERN`에 포함할 전송 필드: `… resultId:%X{resultId:-} outboxId:%X{outboxId:-} claimToken:%X{claimToken:-} attemptNo:%X{attemptNo:-}]`.
- `eventId` 키는 수신 쪽(단말 이벤트 ID)과 전송 쪽(outbox UUID)이 같은 이름을 씁니다. 컨벤션 5.2 표를 따르고 스레드가 달라 섞이지 않습니다.

## 5. 작업

| TASK | REQ | 대상 (경로+심볼) | 변경 내용 | 완료 조건 |
|---|---|---|---|---|
| TASK-01 | REQ-03 | 신규 `delivery/DeliveryMdc`, `delivery/DeliveryService#deliver`, `delivery/OutboxClaimer#claim` | MDC 설정·정리 | TEST-03 |
| TASK-02 | REQ-01, 02, 04 | `DeliveryService#deliver`, `DeliveryRecorder#record`, `OutboxClaimer#claim` | 로그 3종 | TEST-01, 02, 04 |
| TASK-03 | REQ-03 | `gateway/src/main/resources/logback-spring.xml` `LOG_LEVEL_PATTERN` | 전송 키 3개 출력 유지 | TEST-01 |

순서: TASK-01 → TASK-02 → TASK-03

## 6. 테스트

`DeliveryFlowIT`에 `@ExtendWith(OutputCaptureExtension.class)`를 붙여 기존 D1·D9·D7 테스트에 단언을 더합니다.

| TEST | REQ | 조건·입력 | 기대 결과 (로그) | 종류 |
|---|---|---|---|---|
| TEST-01 | REQ-01, 03 | D1 실행 | `DELIVERY_RESULT outcome=RETRYABLE http=503`과 `next=PENDING+`이 있는 줄에 `claimToken:<1차 토큰>`. 이어서 `outcome=SUCCESS http=200 … next=SENT`, `OUTBOX_SENT attempts=2` | integrationTest (`DeliveryFlowIT`) |
| TEST-02 | REQ-02 | D9 / D7 / DL-08 TEST-03 상황 | `OUTBOX_DEAD reason=NON_RETRYABLE` / `reason=MAX_ATTEMPTS` / `outcome=SKIPPED_MAX_ATTEMPTS` | integrationTest |
| TEST-03 | REQ-03 | `runOnce` 뒤 `deliveryExecutor`에 작업을 제출해 `MDC.get("claimToken")` 등 조회 | 네 키 모두 null | integrationTest |
| TEST-04 | REQ-04 | D1 출력 전체 | `OutboxFixture`가 쓴 VIN 문자열 미포함 | integrationTest |

명령: `./gradlew :gateway:integrationTest`

## 7. 현재 구성 반영

2026-11-07 문서 정리 시 출력 패턴의 위치를 Logback 전용 설정으로 반영했습니다. `DeliveryMdc`의 현재 구현에 맞춰 종료 시 기존 MDC를 복원하도록 기술했습니다. 나머지 요구사항과 테스트 표는 원본 계획의 검증 기준이며, 이번 문서 작성에서 테스트를 실행한 결과는 아닙니다.
