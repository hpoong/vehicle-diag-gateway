# DL-02 스케줄러·runOnce 구현 계획

> 문서 상태: 구현 계획 · 문서 정리: 2026-11-07  
> 원본: [계획 자료](../../../../agent/plan-agent/out/02-delivery-retry-design/dl-02-scheduler-run-once/plan.md) · [전체 목차](README.md)  
> 아래 FACT와 BASELINE은 원본 계획 수립 시점의 기록입니다. READY는 계획 준비 상태이며 구현 완료나 테스트 통과를 뜻하지 않습니다.

PLAN_STATUS: READY — 실행 구조(02 §2) 확정. receiver 모듈이 없어 스텁으로 검증(ISSUE-01, 비차단)
BLOCKER: NONE

근거: `temp/설계/02_전송_재처리_설계.md` §2, §5.1 · 구현 순서: **7번째** · 선행: DL-01, DL-03, DL-04, DL-05, DL-06, DL-07 · 후행: DL-08(D7), DL-11(로그), DL-09(D4) · BASELINE: main @ 2125a48

## 1. 요약

`DeliveryService.runOnce()`가 한 회차를 실행합니다. 선점(TX1) → 고정 크기 스레드 풀에서 건마다 전송·분류·기록(TX2) → 전부 끝날 때까지 대기.
`gateway.delivery.enabled=true`(기본)이면 `DeliveryScheduler`가 `poll-interval` 고정 지연으로 `runOnce`를 반복하고, `false`면 스케줄러 빈 자체가 없습니다.
테스트는 스케줄러를 끄고 `runOnce`를 직접 호출해 D1(중단→복구)·D2(응답 유실)·D9(409)를 검증합니다.

## 2. 요구사항

| REQ | 요구 동작 | 수용 기준 |
|---|---|---|
| REQ-01 | 한 회차 실행 | `PENDING` 4건, 스텁 200 → `runOnce()`가 반환될 때 4건 모두 `SENT`, 스텁 수신 4건, 반환 `RunSummary(claimed=4, sent=4)`. 이어서 `runOnce()` → `claimed=0`, 추가 요청 없음 |
| REQ-02 | 스케줄러 등록 조건 | `enabled` 미설정 또는 `true` → `DeliveryScheduler` 빈 있음, `false` → 없음 |
| REQ-03 | 건별 실패 격리 | 2건 중 1건의 기록에서 예외 → 다른 1건은 `SENT`, `runOnce`는 예외 없이 반환, 실패 건은 선점된 채 `PENDING`(lease 만료 후 재선점 대상), `DELIVERY_RECORD_FAILED` ERROR 로그 |
| REQ-04 | D1 수신 서버 중단 | 스텁 503 → `runOnce` → 시도 `RETRYABLE`, 행 `PENDING`. 스텁 200 + `next_attempt_at` 당김 → `runOnce` → `SENT`. 시도 2행 `[RETRYABLE, SUCCESS]` |
| REQ-05 | D2 응답 유실 | 스텁 `hold > readTimeout` → `runOnce` → 시도 `UNCERTAIN READ_TIMEOUT`. 스텁 정상 + 당김 → `runOnce` → `SENT`. 스텁 수신 2건: 본문·`X-Event-Id` 같음, `X-Request-Id` 다름(토큰이 다름) |
| REQ-06 | D9 재시도 불가 | 스텁 409 → `runOnce` → `DEAD(NON_RETRYABLE)`, 시도 1행. 다시 `runOnce` → 추가 요청 0 |

## 3. 계획 수립 당시 구조 (FACT)

- 스케줄링 미사용: `@EnableScheduling` 없음. `GatewayApplication`은 `@SpringBootApplication`만 있습니다.
- `application.yml`: `server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase: 10s`, Hikari `maximum-pool-size: 10`.
- 기존 통합 테스트(`IngestFlowIT` 등)는 outbox가 `PENDING`·`attemptCount=0`으로 남아 있다고 가정합니다. DL-01 TASK-03(integrationTest `config/application.yml`의 `enabled: false`)이 이를 보호합니다.
- receiver 모듈 없음 (`settings.gradle`). DL-04 `testsupport/StubReceiver`가 대역입니다.

## 4. 변경 설계 (PROPOSED)

- `delivery/DeliveryService` (`@Service`, `@Transactional` 없음)
  ```text
  RunSummary runOnce():
    claimed = claimer.claim(props.batchSize())                  // TX1
    futures = claimed.map(e -> CompletableFuture.runAsync(() -> deliver(e), deliveryExecutor))
    CompletableFuture.allOf(futures).join()
    return 집계(SENT / RETRY / DEAD / STALE / FAILED 건수)

  deliver(e):
    try {
      send   = client.send(e.eventId(), e.claimToken(), e.payload())   // 트랜잭션 밖 (DL-04 가드)
      result = recorder.record(e, classifier.classify(send))           // TX2
    } catch (Exception ex) {
      log.error("DELIVERY_RECORD_FAILED", ex)      // 포기 → lease 만료 후 재선점 (02 §7)
    }
  ```
  - 건별 예외는 `deliver` 안에서 잡으므로 `join()`은 항상 정상 종료합니다. 한 건의 실패가 회차 전체를 막지 않습니다.
  - 대기 시간 상한: 각 건은 connect + read 타임아웃(기본 7s) 안에 끝나고, 설정 검증(DL-01)상 이 값은 lease보다 짧습니다.
- 전송 스레드 풀: `DeliveryConfig`에 `@Bean ThreadPoolTaskExecutor deliveryExecutor` — `core = max = batchSize`, 스레드 이름 접두사 `delivery-`. 한 회차의 작업 수가 배치 크기 이하라 큐 대기가 생기지 않습니다. 컨텍스트 종료 시 Spring이 정리합니다(`shutdown-grace`는 02 §11에서 제외).
- `delivery/DeliveryScheduler` — `@Configuration @EnableScheduling @ConditionalOnProperty(prefix = "gateway.delivery", name = "enabled", havingValue = "true", matchIfMissing = true)`, `SchedulingConfigurer` 구현
  - `registrar.addFixedDelayTask(this::tick, props.pollInterval())`. `tick()`은 `runOnce()`를 호출하고 예외를 잡아 ERROR 로그만 남깁니다.
  - `@Scheduled(fixedDelayString=…)` 대신 등록기를 쓰는 이유: 이미 `Duration`으로 바인딩·검증된 `pollInterval`을 그대로 쓰기 위해서입니다. 동작은 02 §2의 `@Scheduled(fixedDelay)`와 같습니다.
  - 스케줄러 스레드는 기본 1개라 회차가 겹치지 않습니다.

## 5. 작업

| TASK | REQ | 대상 (경로+심볼) | 변경 내용 | 완료 조건 |
|---|---|---|---|---|
| TASK-01 | REQ-01, 03 | 신규 `delivery/DeliveryService#runOnce`·`#deliver`, `delivery/RunSummary`, `DeliveryConfig#deliveryExecutor` | 회차 실행·건별 격리·집계 | TEST-01, 03 |
| TASK-02 | REQ-02 | 신규 `delivery/DeliveryScheduler` | 조건부 등록·고정 지연 | TEST-02 |
| TASK-03 | REQ-04~06 | 신규 `DeliveryFlowIT` | D1·D2·D9 시나리오 | TEST-04~06 |

순서: TASK-01 → TASK-02 → TASK-03

## 6. 테스트

`DeliveryFlowIT` 공통: `@SpringBootTest(properties = {"gateway.delivery.backoff.base=0s", "gateway.delivery.read-timeout=500ms", "gateway.delivery.connect-timeout=500ms", "gateway.delivery.max-attempts=3"})`, `@DynamicPropertySource`로 `receiver-url = stub.url()`, `@BeforeEach`에서 `OutboxFixture.reset()`·`stub.reset()`. 백오프 대기는 `UPDATE outbox_event SET next_attempt_at = now() - interval '1 second' WHERE status = 'PENDING'`로 건너뜁니다(`Thread.sleep` 없음, 컨벤션 6.1).

| TEST | REQ | 조건·입력 | 기대 결과 (응답·DB) | 종류 |
|---|---|---|---|---|
| TEST-01 | REQ-01 | `createPending(4)`, 스텁 200 | REQ-01 | integrationTest (`DeliveryFlowIT`) |
| TEST-02 | REQ-02 | `ApplicationContextRunner` + `DeliveryScheduler` + Mock `DeliveryService`, 속성 없음 / `enabled=false` | 빈 1개 / 0개 | 단위 (`DeliverySchedulerTest`) |
| TEST-03 | REQ-03 | `createPending(2)`, `@MockitoSpyBean DeliveryRecorder`가 첫 건 `record`에서 `RuntimeException` | REQ-03 (ERROR 로그는 `OutputCaptureExtension`) | integrationTest |
| TEST-04 | REQ-04 | D1 | REQ-04 | integrationTest |
| TEST-05 | REQ-05 | D2: `stub.hold(2s)` | REQ-05 | integrationTest |
| TEST-06 | REQ-06 | D9: 스텁 409 | REQ-06 | integrationTest |

명령: `./gradlew :gateway:test :gateway:integrationTest` (Docker 필요)

## 7. ISSUE

- ISSUE-01 / 02 §10 D2의 기대 결과 "`quality_case` 1회분"은 receiver DB가 있어야 확인할 수 있습니다. receiver 모듈(03 RC-01)이 아직 없어서 여기서는 스텁으로 "같은 본문·같은 `X-Event-Id`로 2회 전송, 최종 `SENT`"까지만 확인합니다. 실제 receiver로 하는 중복 반영 0건 확인은 04 시나리오 테스트(S5, 정합성 대조)로 넘깁니다 / 비차단 / 3일차 전에 receiver가 준비되면 D2에 receiver를 바로 쓸지 결정
