# 시나리오 검증 결과

- 기준 커밋: `42ffd81f4a208da4b22ae8e0600f50454c3f94a6` + 운영 API·시나리오 구현 작업 트리 변경(미커밋).
- 실행일: 2026-11-07 (Asia/Seoul).
- 환경: macOS aarch64, OpenJDK 17.0.2+8-86, Gradle 8.14.3, Colima Docker, Testcontainers PostgreSQL 16 (`postgres:16-alpine`).
- 실제 실행: 루트에서 `./gradlew clean test integrationTest`.
- 전체 결과: 165개 성공, 실패 0, 건너뜀 0 (단위 50개, 통합 115개).
- 아래 수치와 판정은 위 실행의 JUnit XML `system-out` 내 `SCENARIO_RESULT`에서 추출했다.
- 기본 모드 공통 기준: 정상 접수 N건 = SENT N건 = received_event N건, 이벤트 누락·중복 반영 0, 최종 PENDING·DEAD·DUP1=0, I1~I6 PASS.
- DUP1은 중복된 `(event_id, ecu_id, dtc_code)` 그룹 수다. S12는 비교 모드이므로 received_event 기준을 적용하지 않으며 I3·I4·I5는 SKIP한다.

## 재현 명령

```sh
export JAVA_HOME=/Users/ho_poong/java/jdk-17.0.2.jdk/Contents/Home
export DOCKER_HOST=unix:///Users/ho_poong/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew clean test integrationTest
./gradlew :gateway:integrationTest --tests 'com.hopoong.diag.gateway.scenario.*'
```

다른 환경에서는 JDK·Docker 경로를 맞춘다. Windows는 `gradlew.bat`에 같은 인자를 전달한다. 표의 단일 메서드는 `--tests 'com.hopoong.diag.gateway.scenario.<클래스>.<메서드>'`로 실행한다.

## 시나리오별 결과

| # | 입력 | 장애 모드 | 시도(outcome별) | 반영(received_event) | 중복(DUP1) | 최종 DEAD | I1~I6 | 재현(클래스#메서드) |
|---|---|---|---|---|---|---|---|---|
| S1 | 동일 요청 5개 동시 → 1건 접수 | NONE | {SUCCESS=1} | 1 | 0 | 0 | 모두 PASS | `IngestScenarioIT#s01_concurrentIdenticalRequestsAcceptExactlyOnce` |
| S2 | 동일 키·상이한 DTC → 1건 접수 | NONE | {SUCCESS=1} | 1 | 0 | 0 | 모두 PASS | `IngestScenarioIT#s02_sameKeyDifferentContentDoesNotChangeStoredData` |
| S3 | 11:00 후 10:00 결과 2건 | NONE | {SUCCESS=2} | 2 | 0 | 0 | 모두 PASS | `IngestScenarioIT#s03_latePastResultDoesNotRegressLatest` |
| S4 | 20건 | DOWN → NONE | {RETRYABLE=20, SUCCESS=20} | 20 | 0 | 0 | 모두 PASS | `DeliveryScenarioIT#s04_receiverDownThenRecovers` |
| S5 | 20건 | DROP_AFTER_COMMIT → NONE | {SUCCESS=20, UNCERTAIN=20} | 20 | 0 | 0 | 모두 PASS | `DeliveryScenarioIT#s05_responseLostAfterCommitDoesNotDuplicateEffects` |
| S7 | 1건 선점 후 전송 없이 중단 | lease 만료 | {ABANDONED=1, SUCCESS=1} | 1 | 0 | 0 | 모두 PASS | `DeliveryScenarioIT#s07_workerStopsAfterClaimAndLeaseIsRecovered` |
| S8 | 100건, 선점기 2개 | 동시 선점 후 lease 만료 | {ABANDONED=100, SUCCESS=100} | 100 | 0 | 0 | 모두 PASS | `DeliveryScenarioIT#s08_concurrentClaimersNeverOverlap` |
| S9 | 1건, 이전 토큰 결과 지연 | lease 만료 후 이전 결과 | {ABANDONED=1, SUCCESS=1} | 1 | 0 | 0 | 모두 PASS | `DeliveryScenarioIT#s09_lateSuccessCannotOverwriteNewOwner` |
| S10 | 5건, 최대 3회 후 재처리 | DOWN → requeue → NONE | {RETRYABLE=15, SUCCESS=5} | 5 | 0 | 0 | 모두 PASS | `RequeueScenarioIT#s10_maxAttemptsThenOperatorRequeuesAndDeliveryRecovers` |
| S12 | 20건, 멱등 처리 없는 비교 모드 | DROP_AFTER_COMMIT → NONE | {SUCCESS=20, UNCERTAIN=20} | 0 | 20 | 0 | I1·I2·I6 PASS, I3·I4·I5 SKIP | `NaiveCompareScenarioIT#s12_responseLossDuplicatesEffectsWithoutIdempotency` |

S10의 최종 DEAD는 재처리 후 기준이다. 중간 단계에서 MAX_ATTEMPTS 5건을 요약·목록으로 확인하고 감사 기록 5행 및 사이클 2의 SUCCESS 5행을 테스트에서 검증했다.

## 기본 모드와 비교 모드 (S5 / S12)

| 모드 | 입력 | 응답 유실 후 재시도 | 중복 그룹(DUP1) |
|---|---|---|---|
| 기본(S5) | 20건 | {SUCCESS=20, UNCERTAIN=20} | 0 |
| 비교(S12) | 20건 | {SUCCESS=20, UNCERTAIN=20} | 20 |

같은 장애와 입력 건수에서 기본 모드의 멱등 반영은 중복을 막았으며, 멱등 방어가 없는 비교 모드는 중복 그룹을 생성했다.

## 실행 증거

출처: `gateway/build/test-results/integrationTest/TEST-com.hopoong.diag.gateway.scenario.*.xml`. 재실행 시 빌드 산출물이 덮어써지므로 이번 실행의 원문 요약을 보존한다.

```text
SCENARIO_RESULT scenario=S1 mode=STANDARD results=1 outbox=1 pending=0 sent=1 dead=0 received=1 dup1=0 attempts={SUCCESS=1} I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S2 mode=STANDARD results=1 outbox=1 pending=0 sent=1 dead=0 received=1 dup1=0 attempts={SUCCESS=1} I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S3 mode=STANDARD results=2 outbox=2 pending=0 sent=2 dead=0 received=2 dup1=0 attempts={SUCCESS=2} I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S4 mode=STANDARD results=20 outbox=20 pending=0 sent=20 dead=0 received=20 dup1=0 attempts={RETRYABLE=20, SUCCESS=20} I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S5 mode=STANDARD results=20 outbox=20 pending=0 sent=20 dead=0 received=20 dup1=0 attempts={SUCCESS=20, UNCERTAIN=20} I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S7 mode=STANDARD results=1 outbox=1 pending=0 sent=1 dead=0 received=1 dup1=0 attempts={ABANDONED=1, SUCCESS=1} I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S8 mode=STANDARD results=100 outbox=100 pending=0 sent=100 dead=0 received=100 dup1=0 attempts={ABANDONED=100, SUCCESS=100} I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S9 mode=STANDARD results=1 outbox=1 pending=0 sent=1 dead=0 received=1 dup1=0 attempts={ABANDONED=1, SUCCESS=1} I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S10 mode=STANDARD results=5 outbox=5 pending=0 sent=5 dead=0 received=5 dup1=0 attempts={RETRYABLE=15, SUCCESS=5} I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S12 mode=NAIVE results=20 outbox=20 pending=0 sent=20 dead=0 received=0 dup1=20 attempts={SUCCESS=20, UNCERTAIN=20} I1=PASS I2=PASS I3=SKIP I4=SKIP I5=SKIP I6=PASS
```

## 전체 검증

| 모듈 | 단위 | 통합 | 실패 / 건너뜀 |
|---|---|---|---|
| gateway | 41 | 82 | 0 / 0 |
| receiver | 9 | 25 | 0 / 0 |
| web-support | 0 | 8 | 0 / 0 |

## 결론

정의한 장애 시나리오에서 누락·중복 반영 0건을 확인했다. 이 결론은 기본 모드에서 실행한 시나리오 범위에 한하며 모든 환경에서의 보장을 뜻하지 않는다. S12는 중복 발생을 확인하기 위한 대조군이다.

## 실제 프로세스 재현 (scripts/scenarios.py)

통합 테스트와 같은 판정(I1~I6)을 실제 bootJar 프로세스, 실제 HTTP, Compose PostgreSQL로 다시 확인했다.
S7은 응답을 기다리는 gateway를 `SIGKILL`로 종료한 뒤 다시 띄웠다. S8은 gateway 프로세스 2개를 실행했다.
S9는 gw-1을 `SIGSTOP`으로 멈추고 gw-2가 이어받게 한 뒤 `SIGCONT`로 재개했다.
E1은 시뮬레이터가 기대한 저장 건수와 실제 결과 수가 같은지 판정한다.

- 실행일: 2026-11-07 (Asia/Seoul), macOS aarch64, OpenJDK 17.0.2, Python 3.9.6, Colima Docker `postgres:16-alpine`.
- 명령: `.venv/bin/python -m scripts.scenarios run all`
- gateway 전송 설정(데모용): 백오프 1~4s, 최대 6회, lease 10s, readTimeout 5s(기본값).
- 결과: 10개 시나리오 모두 PASS. S8은 gw-1 100건, gw-2 100건을 성공 처리했고, S9의 늦은 결과 4건은 `late_outcome=SUCCESS`로만 기록됐다.

```text
SCENARIO_RESULT scenario=S1 mode=STANDARD results=40 outbox=40 pending=0 sent=40 dead=0 received=40 dup1=0 attempts={SUCCESS=40} E1=PASS I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S2 mode=STANDARD results=30 outbox=30 pending=0 sent=30 dead=0 received=30 dup1=0 attempts={SUCCESS=30} E1=PASS I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S3 mode=STANDARD results=39 outbox=39 pending=0 sent=39 dead=0 received=39 dup1=0 attempts={SUCCESS=39} E1=PASS I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S4 mode=STANDARD results=20 outbox=20 pending=0 sent=20 dead=0 received=20 dup1=0 attempts={RETRYABLE=44, SUCCESS=20} E1=PASS I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S5 mode=STANDARD results=20 outbox=20 pending=0 sent=20 dead=0 received=20 dup1=0 attempts={SUCCESS=20, UNCERTAIN=4} E1=PASS I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S7 mode=STANDARD results=12 outbox=12 pending=0 sent=12 dead=0 received=12 dup1=0 attempts={ABANDONED=4, SUCCESS=12} E1=PASS I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S8 mode=STANDARD results=200 outbox=200 pending=0 sent=200 dead=0 received=200 dup1=0 attempts={SUCCESS=200} E1=PASS I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S9 mode=STANDARD results=8 outbox=8 pending=0 sent=8 dead=0 received=8 dup1=0 attempts={ABANDONED=4, SUCCESS=8} E1=PASS I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S10 mode=STANDARD results=5 outbox=5 pending=0 sent=5 dead=0 received=5 dup1=0 attempts={RETRYABLE=30, SUCCESS=5} E1=PASS I1=PASS I2=PASS I3=PASS I4=PASS I5=PASS I6=PASS
SCENARIO_RESULT scenario=S12 mode=NAIVE results=20 outbox=20 pending=0 sent=20 dead=0 received=0 dup1=11 attempts={SUCCESS=20, UNCERTAIN=4} E1=PASS I1=PASS I2=PASS I3=SKIP I4=SKIP I5=SKIP I6=PASS
```

## 제외한 시나리오

- S6 (`SLOW`): S5와 같은 응답 불확실성·재전송 성질을 검사하므로 중복 범위에서 제외했다.
- S11 (`compare-no-outbox`): 별도 전송 경로 구현이 필요해 이번 구현 범위에서 제외했다.
