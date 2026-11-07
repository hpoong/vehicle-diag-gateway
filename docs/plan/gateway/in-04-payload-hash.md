# IN-04 정규화·payload_hash 구현

> **문서 상태**: 구현 완료  
> **근거 설계**: 수신 요청 정규화 규칙  
> **선행 작업**: IN-01 (요청 DTO)  
> **연관 작업**: 후행 작업(IN-05 hash 비교, IN-06·07 검증, IN-08 저장, IN-10 outbox payload)의 입력

---

## 1. 개요 및 목적

형식 검증을 통과한 요청을 정규화된 `DiagnosticResultCommand`로 바꾸고, 내용 비교용 `payload_hash`와 원본 보관용 `rawPayload`를 만듭니다.
단말이 같은 내용을 **표현만 바꿔** 재전송해도(시간대 표기, 키 순서, ECU·DTC 순서, `swVersion` null/누락) 같은 hash가 나오게 하는 것이 목적입니다. 이 hash가 IN-05의 "재전송인가, 충돌인가" 판정 기준이 됩니다.

---

## 2. 요구사항 (Requirements)

| ID | 구분 | 요구 동작 및 수용 기준 |
|---|---|---|
| **REQ-01** | **정규화 명령** | • `occurredAt`은 UTC `OffsetDateTime`으로 변환<br/>• `ecus`는 `ecuId` 오름차순, 각 `dtcCodes`도 오름차순<br/>• 각 ECU는 요청에서의 원래 위치 `requestIndex`를 보존 |
| **REQ-02** | **동일 내용 → 같은 hash** | • 표현만 다른 두 요청은 hash가 같고, `dtcCodes`가 하나라도 다르면 다름<br/>• 결과는 소문자 hex 64자 (`^[0-9a-f]{64}$`, DB check 제약과 일치) |
| **REQ-03** | **원본 보관값** | • `rawPayload`는 요청 DTO를 JSON으로 직렬화한 문자열이며 `occurredAt`은 요청 표기 그대로 |

---

## 3. 구현 내용

### 3.1. 정규화 결과 타입 (`ingest` 패키지, record)
- **`DiagnosticResultCommand`**: `deviceId, eventId, brand, vin, sessionId, occurredAt(UTC), ecus`
- **`EcuCommand`**: `ecuId, swVersion(nullable), dtcCodes(정렬됨), requestIndex`
  - `requestIndex`가 필요한 이유: 정렬 후에도 IN-06·07의 오류 응답 `field`(`ecus[i]...`)는 **단말이 보낸 순서**를 가리켜야 하기 때문입니다.
  - 같은 `ecuId`가 반복되면 `requestIndex` 순서를 유지합니다(중복 자체는 IN-06에서 판정).
- **`NormalizedRequest`**: `command, payloadHash, rawPayload`

### 3.2. `DiagnosticResultNormalizer#normalize`
- **hash 입력**: 키를 사전순으로 고정한 `TreeMap`을 compact JSON으로 직렬화 → UTF-8 → SHA-256 → 소문자 hex
  - `occurredAt`은 `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`(UTC)로 통일
  - ECU 항목은 `{dtcCodes, ecuId, swVersion}`. `swVersion`이 null이면 키 자체를 넣지 않아 null과 누락이 같아집니다.
  - `requestIndex`는 hash에 넣지 않습니다(순서만 다른 요청이 같은 hash가 되도록).
- **hash 전용 `ObjectMapper`**: 앱 전역 Jackson 설정이 바뀌어도 hash가 달라지지 않도록, 클래스 안에서 만든 기본 `ObjectMapper`를 씁니다.
- **`rawPayload`**: 주입받은 앱 `ObjectMapper`로 요청 DTO를 직렬화합니다.

---

## 4. 작업 목록 (Tasks)

| 작업 | 대상 파일/클래스 | 작업 내용 |
|---|---|---|
| **TASK-01** | `ingest/DiagnosticResultCommand`, `ingest/EcuCommand`, `ingest/NormalizedRequest` | • 정규화 결과 record 정의 |
| **TASK-02** | `ingest/DiagnosticResultNormalizer` | • 정렬·UTC 변환·hash·rawPayload 생성 |

---

## 5. 검증

- **`DiagnosticResultNormalizerTest`** (단위, DB 불필요)
  - ECU·DTC 정렬, `requestIndex` 보존, UTC 변환, `rawPayload`의 원본 `occurredAt` 표기 확인
  - `+09:00` vs `Z`, ECU·DTC 순서, JSON 키 순서, `swVersion` null vs 누락 → 같은 hash / DTC 하나 추가 → 다른 hash
  - 같은 `ecuId`가 반복될 때 요청 순서 유지

```sh
./gradlew :gateway:test   # Windows: .\gradlew.bat :gateway:test
```
