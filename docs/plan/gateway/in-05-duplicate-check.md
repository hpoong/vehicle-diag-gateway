# IN-05 중복 수신 판정 구현

> **문서 상태**: 구현 완료  
> **근거 설계**: 재전송·충돌 판정 규칙  
> **선행 작업**: IN-01 (수신 API), IN-02 (409 오류 응답), IN-04 (payload_hash)  
> **연관 작업**: IN-08(INSERT 0행 시 재판정에 재사용), IN-11(200 응답 본문)

---

## 1. 개요 및 목적

`(device_id, event_id)`로 기존 결과를 미리 조회해, 단말의 재전송을 판정합니다.

- 기존 결과가 있고 **hash가 같으면** → 재전송(`DUPLICATE`). 기존 결과를 그대로 돌려주고 DB는 바꾸지 않습니다.
- 기존 결과가 있고 **hash가 다르면** → 같은 ID로 다른 내용을 보낸 것이므로 `409 event-conflict`.
- 기존 결과가 없으면 → 다음 단계(검증·저장)로 진행합니다.

이 판정은 공통 규칙·브랜드 정책 검증보다 **먼저** 수행합니다. 이미 받은 요청의 재전송이 이후 검증 규칙 변경 때문에 거부되지 않도록 하기 위함입니다.

---

## 2. 요구사항 (Requirements)

| ID | 구분 | 요구 동작 및 수용 기준 |
|---|---|---|
| **REQ-01** | **같은 키·같은 hash** | • 표현만 다른 재전송 포함, `IngestResult(outcome=DUPLICATE, resultId=기존 ID, ...)` 반환<br/>• DB 행 수 변화 없음 |
| **REQ-02** | **같은 키·다른 hash** | • `409 urn:diag-gateway:error:event-conflict`<br/>• 기존 행의 `payload_hash`는 그대로 |
| **REQ-03** | **판정 공용화** | • 같은 판정 메서드(`judgeExisting`)를 IN-08의 INSERT 0행(동시 요청) 경로에서도 호출 가능 |

---

## 3. 구현 내용

### 3.1. 조회 (`DiagnosticResultMapper#findByKey`)
- 프로젝트의 첫 MyBatis Mapper입니다. `application.yml`에 `mybatis.mapper-locations: classpath:mapper/**/*.xml`을 추가했습니다.
- XML: `gateway/src/main/resources/mapper/ingest/DiagnosticResultMapper.xml`

```sql
SELECT r.id, r.payload_hash, r.policy_code, r.received_at,
       o.event_id::text AS delivery_event_id, o.status AS delivery_status
  FROM diagnostic_result r
  LEFT JOIN outbox_event o ON o.result_id = r.id
 WHERE r.device_id = #{deviceId} AND r.event_id = #{eventId}
```

- **LEFT JOIN을 쓰는 이유**: INNER JOIN이면 outbox가 빠진 결과 행이 "없음"으로 보여 판정이 불가능해집니다. LEFT JOIN이면 결과 행이 있는 한 항상 200/409로 판정되고, outbox 누락은 `INGEST_OUTBOX_MISSING` ERROR 로그로 드러납니다(정상 경로에서는 발생하지 않음).
- **명시적 `<resultMap>` + `<constructor>`**: record에는 기본 생성자가 없고 `uuid` 컬럼용 TypeHandler가 없으므로, `event_id::text`로 받아 서비스에서 `UUID`로 변환합니다.

### 3.2. 판정 (`IngestService`)
- 흐름: `normalize`(IN-04) → `findByKey` → 있으면 `judgeExisting(existing, command, hash)`
- **`IngestResult`** record: `outcome(ACCEPTED | DUPLICATE), resultId, deviceId, eventId, policyCode, receivedAt, deliveryEventId, deliveryStatus`. `ACCEPTED`는 IN-08 저장 완료 후 반환합니다.
- `DUPLICATE`는 IN-11에서 HTTP 200과 `data` 응답 본문으로 변환합니다.

---

## 4. 작업 목록 (Tasks)

| 작업 | 대상 파일/클래스 | 작업 내용 |
|---|---|---|
| **TASK-01** | `gateway/src/main/resources/application.yml` | • `mybatis.mapper-locations` 추가 |
| **TASK-02** | `ingest/DiagnosticResultMapper`, `ingest/ExistingResult`, `mapper/ingest/DiagnosticResultMapper.xml` | • 기존 결과 조회 (LEFT JOIN, 생성자 매핑) |
| **TASK-03** | `ingest/IngestResult`, `ingest/IngestService#ingest`, `#judgeExisting` | • 사전 조회 및 재전송/충돌 판정 |

---

## 5. 검증

- **`DuplicateCheckIT`** (integrationTest, `JdbcTemplate`으로 기존 결과·outbox 픽스처 삽입)
  - 같은 요청, 그리고 키 순서·`Z` 표기·ECU 순서만 바꾼 요청 → 둘 다 `DUPLICATE`, 기존 ID 반환, 행 수 1
  - DTC 하나를 추가한 요청 → HTTP `409 event-conflict`, 기존 `payload_hash` 불변
  - outbox 없이 결과만 있는 경우 → `DUPLICATE`, `deliveryEventId`·`deliveryStatus`는 null, `INGEST_OUTBOX_MISSING` 로그 출력

```sh
./gradlew :gateway:integrationTest   # Windows: .\gradlew.bat :gateway:integrationTest (Docker 필요)
```

## 후속 구현 반영 (2026-11-07)

현재 수신 흐름은 형식 검증 → 정규화 → 중복 판정 → 공통 규칙 → 브랜드 정책 → 단일 트랜잭션 저장입니다. 신규 202 / 중복 200을 반환하며, 결과 ID로 조회할 수 있습니다. 성공 응답은 `data`로 래핑합니다. 상세 상태와 검증 기록은 [구현 현황](README.md)을 참조합니다.
