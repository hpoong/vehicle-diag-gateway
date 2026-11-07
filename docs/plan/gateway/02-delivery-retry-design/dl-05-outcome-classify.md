# DL-05 결과 분류 구현 계획

> 문서 상태: 구현 계획 · 문서 정리: 2026-11-07  
> 원본: [계획 자료](../../../../agent/plan-agent/out/02-delivery-retry-design/dl-05-outcome-classify/plan.md) · [전체 목차](README.md)  
> 아래 FACT와 BASELINE은 원본 계획 수립 시점의 기록입니다. READY는 계획 준비 상태이며 구현 완료나 테스트 통과를 뜻하지 않습니다.

PLAN_STATUS: READY — 분류표(02 §5.2) 확정. 표 밖 경우 2건은 안전한 쪽으로 제안(ISSUE-01·02, 비차단)
BLOCKER: NONE

근거: `temp/설계/02_전송_재처리_설계.md` §5.2 · 구현 순서: **4번째** · 선행: DL-04(`SendResult`) · 후행: DL-07(기록), DL-02(D1·D2·D9) · BASELINE: main @ 2125a48

## 1. 요약

`SendResult`(응답 코드 또는 예외)를 `SUCCESS` / `RETRYABLE` / `UNCERTAIN` / `NON_RETRYABLE`과 `error_code`·`error_message`로 바꾸는 순수 함수 `OutcomeClassifier`를 만듭니다.
`UNCERTAIN`은 처리(재전송)는 `RETRYABLE`과 같지만 "receiver에 이미 반영됐을 수 있음"을 이력에 남기기 위해 따로 둡니다.
시도 결과 enum `AttemptOutcome`도 여기서 정의해 DL-03·07·08·10이 같이 씁니다.

## 2. 요구사항

| REQ | 요구 동작 | 수용 기준 |
|---|---|---|
| REQ-01 | 2xx | 200, 201, 204 → `SUCCESS`, `errorCode=null`, `httpStatus` 유지 |
| REQ-02 | 재시도 가능 HTTP | 408, 429, 500, 503 → `RETRYABLE`, `errorCode = "HTTP_<code>"` (예: `HTTP_503`) |
| REQ-03 | 재시도 불가 HTTP | 400, 404, 409, 422 → `NON_RETRYABLE`, `HTTP_<code>` |
| REQ-04 | 연결 실패 | 원인 사슬에 `ConnectException` 또는 `HttpConnectTimeoutException` → `RETRYABLE`, `CONNECT_FAILED`, `httpStatus=null` |
| REQ-05 | 응답 대기 중 타임아웃 | 원인 사슬에 `HttpTimeoutException`(connect 계열 아님) → `UNCERTAIN`, `READ_TIMEOUT` |
| REQ-06 | 그 밖의 I/O | 원인 사슬에 다른 `IOException` → `UNCERTAIN`, `CONNECTION_CLOSED` |
| REQ-07 | 길이 제한 | `errorMessage`는 1,000자 이하, `errorCode`는 40자 이하. payload 본문은 담지 않음 |

## 3. 계획 수립 당시 구조 (FACT)

- `V2__outbox.sql`
  - `delivery_attempt.outcome` check: `STARTED, SUCCESS, RETRYABLE, UNCERTAIN, NON_RETRYABLE, ABANDONED, SKIPPED_MAX_ATTEMPTS`.
  - `outbox_event.last_outcome` check: `SUCCESS, RETRYABLE, UNCERTAIN, NON_RETRYABLE, SKIPPED_MAX_ATTEMPTS`.
  - `error_code varchar(40)`, `error_message varchar(1000)`, `last_error varchar(1000)`.
- JDK 예외 계층: `HttpConnectTimeoutException extends HttpTimeoutException extends IOException`. 그래서 connect 계열을 **먼저** 검사해야 합니다.
- `RestClient`는 I/O 예외를 `ResourceAccessException`(원인 = 실제 `IOException`)으로 감쌉니다. JDK `ConnectException`은 다른 `IOException`에 한 번 더 감싸여 올 수 있어 원인 사슬 전체를 봅니다.

## 4. 변경 설계 (PROPOSED)

- `delivery/AttemptOutcome` enum: 위 7개 값.
- `delivery/DeliveryOutcome` record: `AttemptOutcome outcome`, `Integer httpStatus`, `String errorCode`, `String errorMessage`, `long latencyMs`.
- `delivery/OutcomeClassifier` (`@Component`, 상태 없음) `DeliveryOutcome classify(SendResult r)`
  ```text
  r.httpStatus != null:
    200..299            → SUCCESS
    408, 429, 500..599  → RETRYABLE      HTTP_<code>
    400..499            → NON_RETRYABLE  HTTP_<code>
    그 밖(1xx·3xx)       → NON_RETRYABLE  HTTP_<code>   (ISSUE-01)
  r.error 원인 사슬:
    HttpConnectTimeoutException | ConnectException → RETRYABLE CONNECT_FAILED
    HttpTimeoutException                           → UNCERTAIN READ_TIMEOUT
    IOException                                    → UNCERTAIN CONNECTION_CLOSED
    그 밖                                           → UNCERTAIN UNEXPECTED_ERROR  (ISSUE-02)
  ```
  - `errorMessage`: HTTP면 `"HTTP <code>"`. 예외면 가장 안쪽 원인의 `getClass().getSimpleName() + ": " + getMessage()`. 1,000자로 자릅니다. 응답 본문은 읽지 않으므로 payload·receiver 응답 내용은 남지 않습니다.
  - 원인 사슬 순회는 자기 참조 사슬에 대비해 깊이 10에서 멈춥니다.

## 5. 작업

| TASK | REQ | 대상 (경로+심볼) | 변경 내용 | 완료 조건 |
|---|---|---|---|---|
| TASK-01 | REQ-01~07 | 신규 `delivery/AttemptOutcome`, `delivery/DeliveryOutcome`, `delivery/OutcomeClassifier#classify` | 4절 분류 | TEST-01~04 |

## 6. 테스트

| TEST | REQ | 조건·입력 | 기대 결과 | 종류 |
|---|---|---|---|---|
| TEST-01 | REQ-01~03 | `SendResult.responded(code)` — 200, 204, 302, 400, 408, 409, 429, 500, 503 | SUCCESS / SUCCESS / NON_RETRYABLE / NON_RETRYABLE / RETRYABLE / NON_RETRYABLE / RETRYABLE / RETRYABLE / RETRYABLE, 코드 `HTTP_<code>` | 단위 (`OutcomeClassifierTest`, `@ParameterizedTest`) |
| TEST-02 | REQ-04~06 | `ResourceAccessException`으로 감싼 `ConnectException`, `IOException(ConnectException)`, `HttpConnectTimeoutException`, `HttpTimeoutException`, `IOException("closed")`, `IllegalStateException` | CONNECT_FAILED ×3 (RETRYABLE), READ_TIMEOUT, CONNECTION_CLOSED (UNCERTAIN), UNEXPECTED_ERROR (UNCERTAIN) | 단위 |
| TEST-03 | REQ-07 | 메시지 5,000자 예외 | `errorMessage.length() == 1000` | 단위 |
| TEST-04 | REQ-04~06 | 실제 소켓: `StubReceiver` hold + read timeout / 미사용 포트 / `closeWithoutResponse()` → `ReceiverClient.send` → `classify` | READ_TIMEOUT / CONNECT_FAILED / CONNECTION_CLOSED | integrationTest (`ReceiverClientIT`에 추가) |

명령: `./gradlew :gateway:test :gateway:integrationTest`

## 7. ISSUE

- ISSUE-01 / 1xx·3xx는 02 §5.2 표에 없습니다. 리다이렉트를 따르지 않으므로(DL-04) 설정 오류로 보고 `NON_RETRYABLE`로 제안합니다 / 비차단 / 재시도로 바꿀지 결정
- ISSUE-02 / I/O가 아닌 예외(클라이언트 내부 오류 등)도 표에 없습니다. 요청이 나갔는지 알 수 없으므로 `UNCERTAIN` + `UNEXPECTED_ERROR`로 제안합니다(receiver 멱등 계약으로 재전송 안전) / 비차단 / `NON_RETRYABLE`로 바로 DEAD 처리를 원하면 결정
