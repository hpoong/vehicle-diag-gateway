# DL-04 HTTP 전송 클라이언트 구현 계획

> 문서 상태: 구현 계획 · 문서 정리: 2026-11-07  
> 원본: [계획 자료](../../../../agent/plan-agent/out/02-delivery-retry-design/dl-04-receiver-client/plan.md) · [전체 목차](README.md)  
> 아래 FACT와 BASELINE은 원본 계획 수립 시점의 기록입니다. READY는 계획 준비 상태이며 구현 완료나 테스트 통과를 뜻하지 않습니다.

PLAN_STATUS: READY — 클라이언트 종류·헤더·타임아웃(02 §5.1, §2.1) 확정
BLOCKER: NONE

근거: `temp/설계/02_전송_재처리_설계.md` §5.1 · receiver 계약 `03_수신서버_설계.md` §3 · 구현 순서: **3번째** · 선행: DL-01 · 후행: DL-05(분류), DL-02(오케스트레이션) · BASELINE: main @ 2125a48

## 1. 요약

저장된 outbox payload를 receiver `POST /api/v1/quality-events`로 그대로 보내는 `ReceiverClient`를 만듭니다.
Spring `RestClient` + JDK `HttpClient`(`JdkClientHttpRequestFactory`)를 쓰고, 라이브러리 자동 재시도는 없습니다.
4xx·5xx는 예외가 아니라 상태 코드로 돌려주고, I/O 예외는 원인째 돌려줘서 분류(DL-05)에 넘깁니다.
receiver 모듈이 아직 없으므로 테스트용 스텁 서버(`StubReceiver`)도 여기서 만듭니다. DL-02·08·09·10이 재사용합니다.

## 2. 요구사항

| REQ | 요구 동작 | 수용 기준 |
|---|---|---|
| REQ-01 | 요청 형식 | `send(eventId, claimToken, payload)` → 스텁이 요청 1건을 받습니다. `POST /api/v1/quality-events`, `Content-Type: application/json`, `X-Event-Id = eventId`, `X-Request-Id = claimToken`, 본문 = 넘긴 문자열과 바이트 단위로 같음 |
| REQ-02 | 상태 코드 반환 | 스텁 응답이 200 / 409 / 503이면 예외 없이 `SendResult.httpStatus()`가 그 값이고 `error()`는 null |
| REQ-03 | 타임아웃·재시도 없음 | 스텁이 `holdMs=3000`, `readTimeout=500ms` → `error()` 원인 사슬에 `HttpTimeoutException`(connect 계열 아님), 스텁 수신 1건. 미사용 포트 → 원인 사슬에 `ConnectException`. 503·타임아웃 모두 요청 횟수 1 |
| REQ-04 | 트랜잭션 밖 호출 강제 | 실제 트랜잭션이 활성인 스레드에서 `send` 호출 → `IllegalStateException`, 요청은 나가지 않음 |

## 3. 계획 수립 당시 구조 (FACT)

- HTTP 클라이언트 코드·의존성 없음. `gateway/build.gradle`의 `spring-boot-starter-web`(Spring Framework 6.2, Boot 3.5.16)에 `RestClient`·`JdkClientHttpRequestFactory`(6.1+)가 포함됩니다.
- payload 원본: `OutboxPayloadFactory#create`가 만든 JSON 문자열을 `OutboxMapper#insert`가 `#{payload}::jsonb`로 저장합니다. 읽을 때는 `payload::text`(jsonb 정규화 표현, 읽을 때마다 같음)가 됩니다.
- receiver 쪽 `RequestIdFilter`(web-support)는 UUID 형식 `X-Request-Id`만 받아들입니다. `claim_token`은 uuid라 그대로 통과합니다.
- receiver 모듈 없음 (`settings.gradle`: `include 'web-support', 'gateway'`, "receiver가 추가되면 include" 주석).
- integrationTest 지원 코드 위치: `gateway/src/integrationTest/java/com/hopoong/diag/testsupport/` (`ErrorProbeController`).

## 4. 변경 설계 (PROPOSED)

- `delivery/ReceiverClient` (`@Component`)
  - 생성자에서 한 번만 구성합니다.
    ```java
    HttpClient http = HttpClient.newBuilder()
        .connectTimeout(p.connectTimeout())
        .version(HttpClient.Version.HTTP_1_1)          // http:// 에서 h2c 업그레이드 시도를 피함
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(p.readTimeout());
    this.rest = RestClient.builder().requestFactory(factory).baseUrl(p.receiverUrl()).build();
    ```
  - `SendResult send(String eventId, String claimToken, String payloadJson)`
    1. `TransactionSynchronizationManager.isActualTransactionActive()`가 true면 `IllegalStateException` (컨벤션 4.2 "HTTP 호출은 트랜잭션 밖").
    2. `System.nanoTime()`으로 소요 시간 측정.
    3. `rest.post().uri("/api/v1/quality-events").contentType(APPLICATION_JSON).header("X-Event-Id", eventId).header("X-Request-Id", claimToken).body(payloadJson).exchange((req, res) -> res.getStatusCode().value())`
       - `exchange`는 상태 코드로 예외를 던지지 않습니다. 응답 본문은 쓰지 않습니다(2xx면 `APPLIED`/`DUPLICATE` 구분 없이 성공, 02 §5.2).
       - `String` 본문은 `StringHttpMessageConverter`가 그대로 씁니다. 다시 직렬화하지 않습니다.
    4. `RestClientException`(I/O는 `ResourceAccessException`)을 잡아 `SendResult.failed(e, latencyMs)`로 반환. 그 밖의 예외도 잡아 같은 방식으로 반환합니다(분류는 DL-05).
- `delivery/SendResult` record: `Integer httpStatus`, `Throwable error`, `long latencyMs`. 정적 생성 `responded(status, ms)`, `failed(error, ms)`.
- 테스트 지원 `testsupport/StubReceiver` (integrationTest, 신규 의존성 없음)
  - `com.sun.net.httpserver.HttpServer`, 포트 0, `Executors.newCachedThreadPool()`(배치 병렬 전송을 동시에 받기 위함).
  - `respond(int status)`, `hold(Duration)`, `requests()`(헤더·본문 기록), `reset()`, `url()`, `close()`.
  - `static int unusedPort()`: `ServerSocket(0)`을 열어 포트를 얻고 닫습니다.
  - `static ClosingServer closeWithoutResponse()`: `ServerSocket`으로 요청을 읽은 뒤 응답 없이 소켓을 닫습니다(DL-05의 `CONNECTION_CLOSED` 재현).

## 5. 작업

| TASK | REQ | 대상 (경로+심볼) | 변경 내용 | 완료 조건 |
|---|---|---|---|---|
| TASK-01 | REQ-01~04 | 신규 `delivery/ReceiverClient#send`, `delivery/SendResult` | 4절 구성·호출·트랜잭션 가드 | TEST-01~05 |
| TASK-02 | REQ-01~03 | 신규 `testsupport/StubReceiver`(+`ClosingServer`) | 스텁 서버 | TEST-01~04 |

순서: TASK-02 → TASK-01

## 6. 테스트

Spring 컨텍스트 없이 `new ReceiverClient(properties)`로 만듭니다. DB가 필요 없어 빠릅니다.

| TEST | REQ | 조건·입력 | 기대 결과 | 종류 |
|---|---|---|---|---|
| TEST-01 | REQ-01 | 스텁 200, payload `{"eventId":"…","vin":"…"}` | status 200, 기록된 요청 1건의 경로·헤더 3종·본문 일치 | integrationTest (`ReceiverClientIT`) |
| TEST-02 | REQ-02 | 스텁 409, 503 | status 409 / 503, `error == null`, 각 요청 1건 | integrationTest |
| TEST-03 | REQ-03 | 스텁 `hold(3s)`, `readTimeout=500ms`, `connectTimeout=500ms` | 원인 사슬에 `HttpTimeoutException`이 있고 `HttpConnectTimeoutException`은 없음, `latencyMs < 3000`, 요청 1건 | integrationTest |
| TEST-04 | REQ-03 | `receiverUrl = http://localhost:{unusedPort}` | 원인 사슬에 `ConnectException` | integrationTest |
| TEST-05 | REQ-04 | `TransactionSynchronizationManager.setActualTransactionActive(true)` 후 `send` (finally에서 false로 복구) | `IllegalStateException`, 스텁 요청 0건 | 단위 (`ReceiverClientTest`) |

명령: `./gradlew :gateway:test :gateway:integrationTest` (integrationTest는 Docker 필요 — 이 클래스 자체는 DB를 쓰지 않음)
