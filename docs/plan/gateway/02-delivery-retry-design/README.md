# Gateway 전송·재시도 설계

기준일: 2026-11-07. `agent/plan-agent/out/02-delivery-retry-design`의 DL-01~11 계획을 기능별 문서로 정리했습니다. 요구사항, 변경 설계, 작업 순서, 테스트 수용 기준과 미결 사항을 보존합니다.

문서의 `PLAN_STATUS: READY`는 원본 계획의 준비 상태입니다. 각 문서의 FACT와 `BASELINE: main @ 2125a48`은 계획 수립 당시 기록이며, 현재 구현 현황이나 테스트 실행 결과를 의미하지 않습니다. DL-11은 현재 Logback 설정 위치와 MDC 복원 방식에 맞춰 보완했습니다.

## 기능별 문서

| 문서 | 구현 순서 | 선행 | 주요 내용 |
|---|---:|---|---|
| [DL-01 전송 설정](dl-01-delivery-config.md) | 1 | 없음 | 설정 기본값·기동 검증·전송기 식별자·테스트 격리 |
| [DL-02 스케줄러·runOnce](dl-02-scheduler-run-once.md) | 7 | DL-01, 03~07 | 회차 실행·병렬 전송·건별 실패 격리 |
| [DL-03 선점 TX1](dl-03-claim.md) | 5 | DL-01, 05 | SKIP LOCKED·토큰·lease·STARTED 이력 |
| [DL-04 HTTP 클라이언트](dl-04-receiver-client.md) | 3 | DL-01 | 저장된 payload 전송·헤더·타임아웃·트랜잭션 가드 |
| [DL-05 결과 분류](dl-05-outcome-classify.md) | 4 | DL-04 | HTTP·I/O 결과 분류와 오류 코드 |
| [DL-06 백오프](dl-06-backoff.md) | 2 | DL-01 | 지수 증가·상한·equal jitter |
| [DL-07 결과 기록 TX2](dl-07-record-result.md) | 6 | DL-03, 05, 06 | 토큰 기반 상태 전이·시도 종료·원자성 |
| [DL-08 최대 시도 초과](dl-08-max-attempts.md) | 8 | DL-02, 03, 07 | 한도 도달 DEAD·초과 선점 시 HTTP 생략 |
| [DL-09 lease 만료 회수](dl-09-lease-reclaim.md) | 10 | DL-02, 03, 07 | 중단 회복·이전 시도 ABANDONED·유효 lease 보호 |
| [DL-10 stale 결과](dl-10-stale-claim.md) | 11 | DL-03, 07, 09, 11 | 이전 토큰 결과 차단·late_outcome 최초 기록 |
| [DL-11 전송 로그](dl-11-delivery-log.md) | 9 | DL-02, 03, 07, 08 | MDC 복원·결과/상태 로그·Logback 출력 패턴 |

권장 구현 순서: **DL-01 → DL-06 → DL-04 → DL-05 → DL-03 → DL-07 → DL-02 → DL-08 → DL-11 → DL-09 → DL-10**.

## 실행 흐름과 트랜잭션

1. 스케줄러가 고정 지연으로 `DeliveryService.runOnce()`를 호출합니다.
2. **TX1**에서 전송 가능한 PENDING 행을 `FOR UPDATE SKIP LOCKED`로 선점하고 새 토큰·lease를 설정하며 시도 번호를 증가시킵니다.
3. 같은 TX1에서 이전 STARTED 이력을 ABANDONED로 종료한 뒤 새 STARTED 이력을 만듭니다. 한도를 초과한 행은 HTTP 없이 DEAD 및 SKIPPED_MAX_ATTEMPTS로 기록합니다.
4. 커밋 후 고정 크기 전송 풀에서 저장된 payload를 receiver로 보냅니다. **HTTP 호출은 DB 트랜잭션 밖에서 실행합니다.**
5. 응답 또는 예외를 분류하고 **TX2**에서 현재 토큰을 확인해 outbox 상태와 시도 결과를 함께 기록합니다.
6. 회차는 모든 전송 작업이 끝난 뒤 집계를 반환합니다. 건별 기록 실패는 로그를 남기고 lease 만료 회수에 맡깁니다.

이전 STARTED 종료는 DL-09까지 미루지 않고 DL-03의 TX1에 포함합니다. 새 STARTED를 먼저 추가하면 outbox당 STARTED 1개 제약을 위반합니다.

## 결과와 재시도 정책

| 결과 | 대표 조건 | 한도 미만 | 한도 도달 |
|---|---|---|---|
| SUCCESS | HTTP 2xx | SENT | SENT |
| RETRYABLE | HTTP 408·429·5xx, 연결 실패 | 백오프 후 PENDING | DEAD(MAX_ATTEMPTS) |
| UNCERTAIN | 응답 타임아웃, 연결 유실 | 백오프 후 PENDING | DEAD(MAX_ATTEMPTS) |
| NON_RETRYABLE | 위 예외를 제외한 4xx | DEAD(NON_RETRYABLE) | DEAD(NON_RETRYABLE) |

1xx·3xx의 NON_RETRYABLE 처리와 그 밖의 예외의 UNCERTAIN 처리는 원본 DL-05의 제안 사항입니다. UNCERTAIN은 receiver에 이미 반영됐을 가능성을 이력에 구분합니다. 재전송은 동일한 `X-Event-Id`와 payload를 사용하고 선점마다 `X-Request-Id`를 새 claim token으로 바꿉니다.

백오프는 `raw(n) = min(base × multiplier^(n−1), max)`, `delay(n) = raw/2 + random(0, raw/2)`입니다. `n`은 방금 실패한 시도의 번호입니다.

lease 만료만으로 결과 기록을 거부하지 않습니다. 재선점으로 토큰이 바뀐 경우 stale로 처리하며, 현재 outbox는 변경하지 않고 이전 ABANDONED 시도에 `late_outcome`을 최초 한 번만 기록합니다.

## 기본 설정

설정 접두사는 `gateway.delivery`이며 기본값은 `DeliveryProperties`의 `@DefaultValue`에서 관리합니다.

| 키 | 기본값 |
|---|---|
| `enabled` | `true` |
| `poll-interval` | `500ms` |
| `batch-size` | `4` |
| `lease` | `30s` |
| `connect-timeout` | `2s` |
| `read-timeout` | `5s` |
| `max-attempts` | `10` |
| `backoff.base` | `5s` |
| `backoff.multiplier` | `2.0` |
| `backoff.max` | `5m` |
| `receiver-url` | `http://localhost:8081` |
| `instance-id` | `GATEWAY_INSTANCE_ID`, 미설정 시 hostname 사용 |

`lease > connect-timeout + read-timeout`을 기동 시 검증합니다. 기존 수신 통합 테스트는 `config/application.yml`의 `gateway.delivery.enabled=false`로 스케줄러 영향을 차단합니다.

로그 출력 패턴은 `gateway/src/main/resources/logback-spring.xml`에서 관리합니다. 수신 추적 필드와 전송 추적 필드를 함께 출력하며 `application.yml`에 패턴을 중복 정의하지 않습니다.

## 검증 계획

| 시나리오 | 확인할 결과 | 문서 |
|---|---|---|
| D1 서버 중단 후 복구 | RETRYABLE → SUCCESS, 최종 SENT | DL-02, 11 |
| D2 응답 유실 | UNCERTAIN 후 동일 이벤트 재전송 | DL-02, 04, 05 |
| D4 처리 중 중단 | 이전 시도 ABANDONED, 새 시도 SUCCESS | DL-09 |
| D5 동시 선점 | 100건 합집합 100개·중복 0 | DL-03 |
| D6 이전 선점자의 늦은 기록 | 현재 토큰·상태 유지, late_outcome 기록 | DL-10 |
| D7 한도 도달 | 503 세 번 후 DEAD, 추가 HTTP 없음 | DL-08 |
| D9 재시도 불가 응답 | 409 한 번 후 DEAD(NON_RETRYABLE) | DL-02, 05 |
| D11 백오프 | 고정 난수로 지수 증가·상한·지터 범위 확인 | DL-06 |

단위 테스트는 설정·스케줄러 조건·결과 분류·백오프를, 통합 테스트는 HTTP 계약·선점·기록·롤백·회복 흐름을 검증합니다. 상세 REQ/TASK/TEST 대응은 각 기능 문서에서 확인합니다.

```sh
# Java 17, 통합 테스트는 Docker 필요
./gradlew :gateway:test :gateway:integrationTest
```

위 명령은 검증 방법이며 이번 문서 작성에서 테스트를 실행하지 않았습니다. 스텁 서버는 요청 재전송까지 검증하며, 실제 receiver DB에서 중복 반영이 없다는 검증은 receiver 연계 시나리오 범위입니다.

## 범위와 관련 자료

수동 재처리 API, 재처리 사이클 초기화, 실제 receiver DB 멱등 반영 검증은 후속 범위입니다. 원본의 인스턴스 ID 키, 분류표 밖 응답, worker_id의 스레드 의미에 대한 제안과 제약은 해당 문서의 ISSUE에 남겼습니다.

- [원본 계획 디렉터리](../../../../agent/plan-agent/out/02-delivery-retry-design/)
- [수신 기능 문서](../01-ingest-design/README.md)
- [Gateway DB 설계](../../../db/gateway-db.md)
- [아키텍처 개요](../../../architecture/overview.md)
- [개발 컨벤션](../../../convention/conventions.md)
