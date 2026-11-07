# 운영 API 및 장애 시나리오 검증

요청 순서대로 구현했다. 기존 Flyway V1~V3와 receiver 운영 코드는 변경하지 않았다.

| 순서 | 단위 | 구현 내용 |
|---|---|---|
| 1 | [vf-01-multi-project](vf-01-multi-project.md) | Gradle 멀티 프로젝트 |
| 2 | [vf-02-it-foundation](vf-02-it-foundation.md) | 통합 테스트 기반 |
| 3 | [vf-03-reconcile](vf-03-reconcile.md) | 정합성 대조 |
| 4 | [op-01-ops-summary](op-01-ops-summary.md) | 운영 요약 API |
| 5 | [op-02-event-list](op-02-event-list.md) | 운영 이벤트 목록 |
| 6 | [op-03-attempt-history](op-03-attempt-history.md) | 전송 시도 이력 |
| 7 | [op-04-requeue](op-04-requeue.md) | 수동 재처리 |
| 8 | [vf-04-scenarios](vf-04-scenarios.md) | 장애 시나리오 |
| 9 | [vf-05-results](vf-05-results.md) | 실행 결과 기록 |

## 실행

JDK 17과 실행 중인 Docker가 필요하다. 루트에서 다음 명령으로 세 모듈의 단위·통합 테스트를 실행한다.

```sh
./gradlew clean test integrationTest
```

시나리오만 실행하려면 다음 명령을 사용한다.

```sh
./gradlew :gateway:integrationTest --tests 'com.hopoong.diag.gateway.scenario.*'
```

Windows는 같은 인자로 `gradlew.bat`을 사용한다. Colima 사용 시 Docker 소켓에 맞는 `DOCKER_HOST`와 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`을 지정한다.

[실행 결과](../../../results.md)에 시나리오별 수치와 재현 메서드를 기록한다.
