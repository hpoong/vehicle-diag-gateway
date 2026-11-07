# Gateway 수신 기능 구현 현황

기준일: 2026-11-07. 구현 순서대로 IN-01~12 수신 기능을 연결했습니다.

| 폴더 | 구현 순서 | 선행 | 현황 | 구현 내용 |
|---|---:|---|---|---|
| [in-01-ingest-api](in-01-ingest-api.md) | 2 | IN-02 | 구현 완료 | 요청 DTO 및 형식 검증 |
| [in-02-error-response](in-02-error-response.md) | 1 | 없음 | 구현 완료 | ProblemDetail 오류 응답과 requestId |
| [in-03-request-log](in-03-request-log.md) | 11 | IN-01, 05~08, 11 | 구현 완료 | 요청 추적 및 판정 INFO 로그 |
| [in-04-payload-hash](in-04-payload-hash.md) | 3 | IN-01 | 구현 완료 | 정규화와 SHA-256 멱등성 해시 |
| [in-05-duplicate-check](in-05-duplicate-check.md) | 4 | IN-01, 02, 04 | 구현 완료 | 동일 요청 200 / 다른 내용 409 |
| [in-06-common-rule](in-06-common-rule.md) | 6 | IN-04, 05, 07 | 구현 완료 | 브랜드와 무관한 공통 검증 |
| [in-07-brand-policy](in-07-brand-policy.md) | 5 | IN-02, 04, 05 | 구현 완료 | BRAND_A 필수 버전 및 기본 정책 |
| [in-08-save-transaction](in-08-save-transaction.md) | 8 | IN-04~07, 10 | 구현 완료 | 결과·관측·최신 상태·outbox 원자적 저장 |
| [in-09-latest-upsert](in-09-latest-upsert.md) | 9 | IN-08 | 구현 완료 | (진단 시각, 결과 ID) 조건부 최신값 갱신 |
| [in-10-outbox-payload](in-10-outbox-payload.md) | 7 | IN-04 | 구현 완료 | receiver 전송 본문 및 이벤트 UUID 생성 |
| [in-11-ingest-response](in-11-ingest-response.md) | 10 | IN-01, 05, 08 | 구현 완료 | 202 ACCEPTED / 200 DUPLICATE 성공 응답 |
| [in-12-result-query](in-12-result-query.md) | 12 | IN-02, 08, 10, 11 | 구현 완료 | 결과·ECU·현재 전송 상태 조회 |

## 실행 흐름

POST 형식 검증 → 정규화/hash → 기존 키 조회 → 같은 hash면 200, 다른 hash면 409 → 신규 요청 공통 규칙 → 브랜드 정책 → 결과·관측·최신 상태·outbox를 한 트랜잭션으로 저장 → 202.

저장 경합으로 INSERT가 0행이면 트랜잭션 밖에서 기존 결과를 재조회해 200/409를 판정합니다. GET은 결과와 ECU 관측, 현재 outbox 상태를 반환합니다.

## 범위

이번 구현은 수신 및 조회 기능입니다. outbox 본문과 전송 예약은 저장하지만 receiver HTTP 전송 워커·재시도·운영자 재처리는 별도 구현 범위입니다. 202는 접수 완료이며 전송 완료가 아닙니다.

## 검증 기록

2026-11-07 실행 결과: **BUILD SUCCESSFUL**.

- 단위 테스트 10개, 통합 테스트 20개 통과 (실패·오류·스킵 0)
- Java 17 + Colima Docker + PostgreSQL 16 Testcontainers 사용
- 동시 POST 5개 → 202 1개 / 200 4개 및 동일 결과 ID 확인
- outbox 실패·checked 직렬화 예외 시 네 테이블 전체 롤백 확인
- 지연 도착·같은 시각의 결과 ID 비교·신규 시각·빈 DTC 최신값 확인
- BRAND_A 및 기본 정책 저장, 전송 상태 조회·중복 응답 반영, 400/404/409/422 확인
- 각 판정 INFO 로그 한 줄, requestId 상관관계·요청 후 MDC 정리 확인
- HTML: 일반용 9장 / 개발자용 13장, 데스크톱·모바일 가로 넘침 없음과 슬라이드 이동 확인

```sh
# Colima 환경의 실행 예시 (JAVA_HOME은 로컬 Java 17 경로)
JAVA_HOME=/Users/ho_poong/java/jdk-17.0.2.jdk/Contents/Home \
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :gateway:test :gateway:integrationTest
```


## 흐름 발표 자료

- [일반 설명용](../../flow/gateway-overview.html)
- [개발자 설명용](../../flow/gateway-developer.html)

브라우저에서 직접 열 수 있는 독립 HTML입니다. 좌우 화살표로 이동하고 인쇄 시 전체 슬라이드를 출력할 수 있습니다.
