# Receiver 구현 현황

receiver는 gateway의 품질 이벤트를 PostgreSQL 16에 멱등 반영합니다. Java 17, Spring Boot, MyBatis, Flyway를 사용하며 gateway와 업무 모델·DB를 공유하지 않습니다.

## 구현 순서와 기능

| 순서 | 항목 | 구현 |
|---|---|---|
| 1 | RC-01 프로젝트·스키마 | receiver DB 연결, Flyway V1, 3개 테이블, PostgreSQL 통합 테스트 기반 |
| 2 | RC-03 오류 응답 | 공통 RFC 9457 응답과 receiver 전용 event-conflict |
| 3 | RC-02 API·검증 | 요청 DTO·중첩 필드 검증·ECU/DTC 쌍 중복 거부 |
| 4 | RC-04 payload_hash | 객체 키 사전순, null 생략, 배열 순서 유지, UTF-8 SHA-256 |
| 5 | RC-05 중복 제거 TX | 이벤트 선점·품질 건 INSERT 원자성, 동일 내용 재수신·충돌 판정 |
| 6 | RC-06 장애 API | chaos 전용 모드 설정, AtomicReference 상태 |
| 7 | RC-07 DOWN | 본문 파싱 전 503, admin·health 경로 유지 |
| 8 | RC-08 DROP_AFTER_COMMIT | 커밋 완료 후 성공 응답 보류, 트랜잭션 내부 대기 금지 |
| 9 | RC-09 naive | 중복 제거 없이 별도 테이블에 반복 INSERT |
| 10 | RC-10 판정 로그 | 판정·장애 로그와 requestId·eventId MDC |

## 품질 이벤트 API

`POST /api/v1/quality-events`는 gateway outbox의 JSON을 수신합니다.

```json
{
  "eventId": "00000000-0000-0000-0000-000000000001",
  "source": {"system": "diag-gateway", "resultId": 42},
  "vin": "KMHXX00XXXX000001",
  "brand": "A",
  "occurredAt": "2026-11-05T23:10:00.000Z",
  "ecus": [{"ecuId": "BMS", "swVersion": "1.2", "dtcCodes": ["P0A80"]}]
}
```

- 필수: UUID eventId, 양수 source.resultId, vin(최대 17), 오프셋 포함 occurredAt, ecus, ecuId(최대 30), dtcCodes(각 최대 10).
- swVersion은 선택이며 최대 30자입니다. receivedAt은 선택이며 있으면 오프셋 포함 시각입니다.
- 빈 ecus·dtcCodes를 허용합니다. 같은 ECU·DTC 쌍은 두 번째 위치부터 `DUPLICATE_CASE`로 400을 반환합니다.
- 알 수 없는 필드·깨진 JSON·null ECU·형식 오류는 400입니다. X-Event-Id 일치 검사는 하지 않습니다.
- 성공: `200 {"data":{"eventId":"...","result":"APPLIED","caseCount":1}}`.
- 동일 ID·동일 hash는 `200 DUPLICATE`와 저장된 caseCount를 반환합니다.
- 동일 ID·다른 hash는 `409 urn:diag-receiver:error:event-conflict`입니다.
- 오류는 `application/problem+json`, 접두사는 `urn:diag-receiver:error:`입니다. UUID X-Request-Id를 재사용합니다.

해시는 DTO의 모든 객체 키를 사전순으로 정렬하고 null을 생략한 공백 없는 JSON으로 계산합니다. 배열 순서와 시각 문자열 표기를 유지합니다. 따라서 swVersion null·생략은 같고, ECU 순서나 `.000Z`와 `Z` 표기는 다릅니다. eventId 대소문자도 원문 기준으로 hash에 포함합니다.

## 트랜잭션과 비교 모드

별도 writer 빈의 트랜잭션에서 `received_event`를 `ON CONFLICT DO NOTHING RETURNING`으로 선점하고, 신규 이벤트일 때 품질 건을 일괄 INSERT합니다. 품질 건 저장 실패·행 수 불일치는 이벤트까지 롤백합니다. 동시 수신은 PostgreSQL PK와 READ COMMITTED 문장별 가시성으로 한 번만 반영됩니다.

`receiver.compare.naive-insert=true`이면 received_event와 hash 판정 없이 quality_case_naive에 저장하고 항상 APPLIED를 반환합니다. 기본값은 false입니다. 검증과 장애 처리는 두 모드에 공통 적용됩니다.

```sql
select count(*) from (
  select 1 from quality_case_naive
  group by event_id, ecu_id, dtc_code having count(*) >= 2
) g;
```

같은 3건 이벤트를 3회 보내면 기본 모드는 품질 건 3행·중복 그룹 0, naive는 9행·중복 그룹 3입니다.

## 장애 주입과 관측

chaos 프로필에서만 `POST /admin/faults`가 열립니다.

```json
{"mode":"DROP_AFTER_COMMIT","holdMs":15000}
```

모드는 NONE, DOWN, DROP_AFTER_COMMIT입니다. holdMs 기본값은 15000, 허용 범위는 0~60000입니다. 설정은 프로세스 메모리에만 유지하며 모든 대상 요청에 적용합니다. 조회 API는 없습니다.

DOWN은 품질 API 인터셉터에서 본문을 읽기 전에 503으로 거절합니다. admin과 health는 계속 사용할 수 있습니다. DROP_AFTER_COMMIT은 writer 트랜잭션 반환 후 APPLIED·DUPLICATE 응답을 보류합니다. 400·409는 즉시 반환합니다. 이미 시작한 보류는 설정을 NONE으로 바꿔도 해당 스냅샷의 holdMs를 따릅니다. 보류 중 DB 커넥션을 점유하지 않습니다.

판정 로그는 커밋 후·보류 전에 QUALITY_APPLIED, QUALITY_DUPLICATE, QUALITY_CONFLICT를 한 줄 남깁니다. naive에는 naive=true를 표시합니다. 장애 시 FAULT_APPLIED와 mode·holdMs를 기록합니다. 로그에 requestId·eventId를 표시하고 VIN·payload는 기록하지 않습니다. DOWN은 본문을 읽지 않아 eventId가 비어 있습니다.

## 검증

```sh
./gradlew :receiver:test :receiver:integrationTest
```

단위 테스트는 Docker 없이 실행하며 hash·검증·설정·트랜잭션 내 대기 방지·MDC 정리를 확인합니다. 통합 테스트는 Testcontainers PostgreSQL 16과 실제 HTTP로 마이그레이션·제약·동시 수신·롤백·응답 보류·재전송·naive 비교·로그를 확인합니다. 공통 오류·장애 설정 API의 MockMvc 테스트는 DB 없이 실행합니다.

실행 환경과 기존 볼륨의 receiver DB 추가 절차는 [루트 README](../../../../README.md), 테이블 정의는 [receiver DB](../../../db/receiver-db.md)를 참고하세요.

### 검증 결과 (2026-11-07)

- receiver 단위 테스트 9건·PostgreSQL 통합 테스트 25건 통과, receiver build 성공.
- 동시 수신 10건은 APPLIED 1건·DUPLICATE 9건이며 품질 건은 3행 유지.
- 커밋 후 클라이언트 타임아웃 뒤 재전송은 기본 모드에서 DUPLICATE, naive에서는 APPLIED로 중복 3행 추가.
- 별도 임시 Compose 프로젝트의 빈 볼륨에서 기본·따옴표 포함 비밀번호 모두 DB 초기화·receiver JAR 기동·Flyway V1·health UP 확인.
- gateway·web-support의 기존 테스트 태스크도 성공 상태 확인.
