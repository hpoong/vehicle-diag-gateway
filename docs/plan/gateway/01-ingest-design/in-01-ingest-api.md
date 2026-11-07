# IN-01 수신 API·요청 DTO 구현

> **문서 상태**: 구현 완료  
> **근거 설계**: 수신 API 요청 형식 규칙  
> **선행 작업**: IN-02 (오류 응답 형식)  
> **연관 작업**: 후행 작업(IN-04 정규화, IN-05 중복 판정, IN-11 성공 응답 변환)의 진입점

---

## 1. 개요 및 목적

`POST /api/v1/diagnostic-results` 엔드포인트와 요청 DTO를 만들고, Bean Validation으로 요청 형식을 검증합니다.
형식 위반은 모두 `400 invalid-request`로 응답하며, 위반 항목은 하나도 빠짐없이 `errors[]`에 담습니다. 형식 검증에 실패한 요청은 서비스 계층까지 내려가지 않습니다.

---

## 2. 요구사항 (Requirements)

| ID | 구분 | 요구 동작 및 수용 기준 |
|---|---|---|
| **REQ-01** | **필드 형식 검증** | • 형식 규칙을 하나라도 어기면 `400 urn:diag-gateway:error:invalid-request`<br/>• `errors[]`에 위반 전부 포함, `field`는 중첩 경로(예: `ecus[0].dtcCodes[1]`) |
| **REQ-02** | **계약 밖 입력 거부** | • 정의되지 않은 필드, 깨진 JSON, `occurredAt`의 오프셋 누락·숫자 epoch, `ecus` 배열 안의 `null`은 모두 400 |

---

## 3. 구현 내용

### 3.1. 요청 DTO (`ingest` 패키지, record)

| 필드 | 검증 규칙 |
|---|---|
| `deviceId` | 필수, 1~50자, `^[A-Za-z0-9_-]+$` |
| `eventId` | 필수, 1~64자, `^[A-Za-z0-9_-]+$` |
| `brand` | 필수, 1~20자, `^[A-Z0-9_]+$` |
| `vin` | 필수, 17자, `I`·`O`·`Q` 제외 영숫자 |
| `sessionId` | 필수, 1~64자 |
| `occurredAt` | 필수, 오프셋이 있는 ISO-8601 문자열 (`@IsoOffsetDateTime`) |
| `ecus` | 필수, 최대 30개, 원소 `null` 불가. 빈 배열은 형식상 통과 (공통 규칙 IN-06에서 판정) |
| `ecus[].ecuId` | 필수, 1~30자, `^[A-Z0-9_]+$` |
| `ecus[].swVersion` | 선택. 값이 있으면 1~30자 (`""`는 400) |
| `ecus[].dtcCodes` | 필수, 최대 50개, 각 코드 `^[PCBU][0-3][0-9A-F]{3}$` |

### 3.2. `occurredAt`을 문자열로 받는 이유
- `OffsetDateTime` 타입으로 받으면 Jackson이 숫자 epoch도 받아들이고, 파싱 실패 시 `errors[]` 없는 400이 됩니다.
- 원본 표기를 `raw_payload`에 그대로 남겨야 합니다(IN-04).
- 그래서 문자열로 받고 아래 두 장치로 형식을 강제합니다.
  - **`@IsoOffsetDateTime`** (`ingest/validation`): `ISO_OFFSET_DATE_TIME` 파싱에 실패하면 위반입니다. `null`은 `@NotNull`이 따로 검사하므로 통과시킵니다.
  - **`StrictStringDeserializer`** (`ingest/validation`): JSON 문자열 토큰만 받습니다. 숫자(`1730000000`)는 역직렬화 단계에서 400으로 거부합니다. 계획서에는 없던 장치로, Jackson이 숫자를 문자열로 자동 변환하는 것을 막기 위해 추가했습니다.

### 3.3. 컨트롤러·서비스
- **`DiagnosticResultController`**: `@Valid @RequestBody`로 바인딩한 뒤 `IngestService.ingest(request)`에 위임합니다.
- 알 수 없는 필드는 `application.yml`의 `fail-on-unknown-properties: true`로 거부됩니다(기존 설정).
- 성공 응답은 IN-11 구현을 반영해 신규 `202` / 중복 `200`과 `ApiResponse<IngestResponse>` 본문을 반환합니다.

---

## 4. 작업 목록 (Tasks)

| 작업 | 대상 파일/클래스 | 작업 내용 |
|---|---|---|
| **TASK-01** | `ingest/DiagnosticResultRequest`, `ingest/EcuRequest` | • 요청 record와 검증 어노테이션 |
| **TASK-02** | `ingest/validation/IsoOffsetDateTime`, `IsoOffsetDateTimeValidator`, `StrictStringDeserializer` | • 오프셋 필수 ISO 검증, 숫자 epoch 거부 |
| **TASK-03** | `ingest/DiagnosticResultController`, `ingest/IngestService` | • 엔드포인트와 서비스 위임 |

---

## 5. 검증

- **`IngestRequestValidationIT`** (integrationTest)
  - 한 요청의 여러 위반(`vin` 길이, 잘못된 DTC, `swVersion: ""`)이 `errors[]`에 모두 담기는지 확인
  - `ecus` 31개, `deviceId` 51자, 알 수 없는 필드, 깨진 JSON, 숫자 epoch, `ecus: [null]` 거부 확인
  - 오프셋 없는 `occurredAt`은 `errors[0].field = occurredAt`으로 응답하는지 확인
  - `ecus: []`는 형식 검증을 통과해 서비스까지 전달되는지 확인
- **`IsoOffsetDateTimeValidatorTest`** (단위): `Z`·`+09:00` 통과, 오프셋 없음·잘못된 월 실패, `null` 통과

```sh
./gradlew :gateway:test :gateway:integrationTest   # Windows: .\gradlew.bat ...
```

## 후속 구현 반영 (2026-11-07)

현재 수신 흐름은 형식 검증 → 정규화 → 중복 판정 → 공통 규칙 → 브랜드 정책 → 단일 트랜잭션 저장입니다. 신규 202 / 중복 200을 반환하며, 결과 ID로 조회할 수 있습니다. 성공 응답은 `data`로 래핑합니다. 상세 상태와 검증 기록은 [구현 현황](README.md)을 참조합니다.
