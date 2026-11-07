# DL-01 전송 설정 구현 계획

> 문서 상태: 구현 계획 · 문서 정리: 2026-11-07  
> 원본: [계획 자료](../../../../agent/plan-agent/out/02-delivery-retry-design/dl-01-delivery-config/plan.md) · [전체 목차](README.md)  
> 아래 FACT와 BASELINE은 원본 계획 수립 시점의 기록입니다. READY는 계획 준비 상태이며 구현 완료나 테스트 통과를 뜻하지 않습니다.

PLAN_STATUS: READY — 키·기본값은 02 §2.1 표로 확정. 인스턴스 ID 키만 제안(ISSUE-01, 비차단)
BLOCKER: NONE

근거: `temp/설계/02_전송_재처리_설계.md` §2, §2.1, §8 · 구현 순서: **1번째** · 선행: 없음 · 후행: DL-02~11 전부 · BASELINE: main @ 2125a48

## 1. 요약

`gateway.delivery.*` 설정을 불변 record `DeliveryProperties`로 바인딩하고, 값이 없으면 02 §2.1 기본값을 씁니다.
잘못된 값(0건 배치, lease가 요청 타임아웃보다 짧음 등)은 기동 시점에 실패시킵니다.
전송기 식별자(`locked_by`, `worker_id`)를 만드는 `WorkerIdentity`도 여기서 만듭니다.
DL-02부터 스케줄러가 생기므로, 기존 통합 테스트가 영향받지 않도록 integrationTest 전용 설정으로 전송기를 미리 꺼 둡니다.

## 2. 요구사항

| REQ | 요구 동작 | 수용 기준 |
|---|---|---|
| REQ-01 | 기본값 바인딩 | `gateway.delivery.*` 키가 하나도 없을 때 `enabled=true`, `pollInterval=500ms`, `batchSize=4`, `lease=30s`, `connectTimeout=2s`, `readTimeout=5s`, `maxAttempts=10`, `backoff=(5s, 2.0, 5m)`, `receiverUrl=http://localhost:8081` |
| REQ-02 | 값 재정의 | `gateway.delivery.poll-interval=100ms`, `backoff.base=0s`, `max-attempts=3`이면 그 값으로 바인딩됩니다 |
| REQ-03 | 잘못된 값 거부 | `batch-size=0`, `max-attempts=0`, `backoff.multiplier=0.5`, `backoff.base=-1s`, `lease=5s`(≤ connect 2s + read 5s), `receiver-url` 공백 중 하나라도 있으면 컨텍스트 기동이 실패합니다 |
| REQ-04 | 전송기 식별자 | `GATEWAY_INSTANCE_ID=gw-1`이면 `instanceId()="gw-1"`, `workerId("delivery-2")="gw-1:delivery-2"`. 미설정이면 hostname(비어 있지 않음). 64자/100자를 넘으면 잘라냅니다 |
| REQ-05 | 테스트 격리 | integrationTest 실행 시 `gateway.delivery.enabled=false`가 기본 적용됩니다 |

## 3. 계획 수립 당시 구조 (FACT)

- `gateway/src/main/resources/application.yml`: `gateway.*` 키 없음. `diag.web.problem-type-prefix`만 사용자 정의 키입니다.
- `@ConfigurationProperties`·`@EnableScheduling`·`RestClient` 사용처 없음 (`gateway/src`, `web-support/src` 검색).
- `gateway/build.gradle`: `spring-boot-starter-validation` 포함 → `@Validated` 설정 검증 가능. Boot 3.5.16.
- `V2__outbox.sql`: `outbox_event.locked_by varchar(64)`, `delivery_attempt.worker_id varchar(100)`.
- `gateway/src/integrationTest/resources` 디렉터리 없음. 통합 테스트는 `@SpringBootTest` + 클래스별 `PostgreSQLContainer`(`IngestFlowIT`, `IngestRollbackIT`).
- `IngestFlowIT#acceptsQueriesAndReturnsCurrentDeliveryOnDuplicate`가 `delivery.attemptCount == 0`, `status == PENDING`을 단언합니다. 스케줄러가 돌면 깨집니다.

## 4. 변경 설계 (PROPOSED)

- `delivery/DeliveryProperties` — `@ConfigurationProperties("gateway.delivery") @Validated record`
  - 필드: `boolean enabled`, `Duration pollInterval`, `@Min(1) int batchSize`, `Duration lease`, `Duration connectTimeout`, `Duration readTimeout`, `@Min(1) int maxAttempts`, `@Valid Backoff backoff`, `@NotBlank String receiverUrl`, `String instanceId`
  - 기본값은 생성자 파라미터의 `@DefaultValue`에만 둡니다(`application.yml`에 중복 기재하지 않음).
  - `record Backoff(Duration base, @DecimalMin("1.0") double multiplier, Duration max)` — `base ≥ 0`, `max ≥ base`는 `@AssertTrue`로 확인합니다.
  - `@AssertTrue boolean isLeaseLongerThanRequestTimeout()`: `lease > connectTimeout + readTimeout`. 02 §8 "전송 1건은 최대 connect + read 안에 끝나므로 고정 lease로 충분하다"는 전제를 설정으로 강제합니다.
  - Duration 값이 0 이하(`pollInterval`, `lease`, 타임아웃)이면 거부합니다.
- `delivery/DeliveryConfig` — `@Configuration @EnableConfigurationProperties(DeliveryProperties.class)`, `WorkerIdentity` 빈 등록.
- `delivery/WorkerIdentity`
  - `instanceId()`: `properties.instanceId()`가 비어 있지 않으면 그 값. 아니면 `InetAddress.getLocalHost().getHostName()`. 예외가 나면 `"gateway"`. 64자로 자릅니다(`locked_by`).
  - `workerId(String threadName)`: `instanceId + ":" + threadName`, 100자로 자릅니다(`worker_id`).
- `application.yml`에 `gateway.delivery.instance-id: ${GATEWAY_INSTANCE_ID:}` 한 줄만 추가합니다.
- `gateway/src/integrationTest/resources/config/application.yml`(신규): `gateway.delivery.enabled: false`.
  - `classpath:/config/application.yml`은 `classpath:/application.yml`에 더해 로드되고 같은 키를 덮어씁니다. 같은 이름의 `application.yml`을 테스트 리소스 루트에 두면 main 설정과 충돌하므로 `config/` 아래에 둡니다.

## 5. 작업

| TASK | REQ | 대상 (경로+심볼) | 변경 내용 | 완료 조건 |
|---|---|---|---|---|
| TASK-01 | REQ-01~03 | 신규 `delivery/DeliveryProperties`, `delivery/DeliveryConfig` | 4절 record·검증·등록 | TEST-01~03 |
| TASK-02 | REQ-04 | 신규 `delivery/WorkerIdentity`, `application.yml` | 인스턴스 ID 해석·잘라내기, `instance-id` 키 | TEST-04 |
| TASK-03 | REQ-05 | 신규 `gateway/src/integrationTest/resources/config/application.yml` | `gateway.delivery.enabled: false` | TEST-05 |

순서: TASK-01 → TASK-02 → TASK-03

## 6. 테스트

| TEST | REQ | 조건·입력 | 기대 결과 | 종류 |
|---|---|---|---|---|
| TEST-01 | REQ-01 | `ApplicationContextRunner` + `ValidationAutoConfiguration` + `DeliveryConfig`, 속성 없음 | REQ-01의 기본값 전부 | 단위 (`DeliveryPropertiesTest`) |
| TEST-02 | REQ-02 | `poll-interval=100ms`, `backoff.base=0s`, `max-attempts=3` | 바인딩 값 일치 | 단위 |
| TEST-03 | REQ-03 | REQ-03의 잘못된 값을 하나씩 | `context.getStartupFailure()`가 null 아님 (6가지 각각) | 단위 (`@ParameterizedTest`) |
| TEST-04 | REQ-04 | `instance-id=gw-1` / 미설정 / 80자 | `gw-1`, `gw-1:delivery-2` / 공백 아닌 hostname / 64자 | 단위 |
| TEST-05 | REQ-05 | 기존 `GatewayApplicationIT` 컨텍스트에서 `DeliveryProperties` 주입 | `enabled() == false` | integrationTest |

명령: `./gradlew :gateway:test` · `./gradlew :gateway:integrationTest` (Docker 필요, Windows는 `.\gradlew.bat`)

## 7. ISSUE

- ISSUE-01 / 인스턴스 ID 설정 키는 02 §2.1 표에 없습니다(`GATEWAY_INSTANCE_ID` 환경 변수만 언급). `gateway.delivery.instance-id`로 바인딩하는 안을 제안합니다 / 비차단 / 다른 키 이름을 원하면 결정
