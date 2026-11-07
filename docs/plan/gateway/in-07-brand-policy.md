# IN-07 브랜드 정책 구현

> **문서 상태**: 구현 완료  
> **근거 설계**: 브랜드별 검증 정책 규칙  
> **선행 작업**: IN-02 (`policyCode` 확장 속성), IN-04 (정규화 명령), IN-05 (중복 판정)  
> **연관 작업**: IN-06(공통 규칙, 정책보다 앞에서 실행), IN-08(`policy_code` 저장)

---

## 1. 개요 및 목적

브랜드마다 다른 검증 규칙을 버전이 붙은 정책(`STANDARD-v1`, `BRAND_A-v1`)으로 분리하고, 요청의 `brand`로 적용할 정책을 고릅니다. 등록되지 않은 브랜드에는 `STANDARD-v1`을 적용합니다.
정책을 위반하면 `422 policy-violation`에 어떤 정책으로 판정했는지(`policyCode`)를 함께 응답합니다. 통과하면 적용한 정책 코드를 저장 단계(IN-08)로 넘겨 `diagnostic_result.policy_code`에 남깁니다.

서비스 안의 실행 위치는 **중복 판정 → 공통 규칙(IN-06) → 브랜드 정책** 순입니다.

---

## 2. 요구사항 (Requirements)

| ID | 구분 | 요구 동작 및 수용 기준 |
|---|---|---|
| **REQ-01** | **정책 선택** | • `BRAND_A` → `BRAND_A-v1`<br/>• 등록되지 않은 브랜드(예: `ZETA`) → `STANDARD-v1` |
| **REQ-02** | **BRAND_A 규칙** | • `swVersion`이 없는 ECU마다 위반 1건 (rule `BRAND_A.SW_VERSION_REQUIRED`)<br/>• `422 policy-violation`, `policyCode = BRAND_A-v1`, `errors[].field`는 요청 순서 기준(`ecus[0].swVersion`)<br/>• DB에는 아무것도 저장되지 않음 |
| **REQ-03** | **레지스트리 무결성** | • 같은 브랜드를 가진 정책이 2개이거나 `STANDARD-v1`이 없거나 2개면 애플리케이션 기동이 `IllegalStateException`으로 실패 |
| **REQ-04** | **적용 코드 전달** | • 통과한 요청은 선택된 정책 코드가 저장 단계로 전달됨 (DB 저장 확인은 IN-08) |

---

## 3. 구현 내용

### 3.1. 정책 계층 (`policy` 패키지)
- **`Violation(field, rule, message)`**: 정책 계층은 web 타입(`ApiFieldError`)을 모르게 두고, 서비스가 변환합니다.
- **`ResultValidationPolicy`** 인터페이스: `code()`, `brands()`, `validate(DiagnosticResultCommand)`
  - 위반을 모두 모아 반환하고 예외는 던지지 않습니다.
  - 공통 규칙을 끄는 메서드는 의도적으로 두지 않았습니다. 어떤 정책도 공통 규칙(IN-06)을 우회할 수 없습니다.
- **`StandardPolicy`**: `STANDARD-v1`, 담당 브랜드 없음(기본값 전용), 위반 없음
- **`BrandAPolicy`**: `BRAND_A-v1`, 담당 브랜드 `BRAND_A`, `swVersion == null`인 ECU마다 위반 반환. `field`는 정렬 전 위치인 `requestIndex`로 만듭니다.

### 3.2. `PolicyRegistry`
- 스프링이 주입한 모든 `ResultValidationPolicy` 빈으로 `brand → policy` 맵을 만듭니다. 새 정책은 `@Component` 빈을 추가하는 것만으로 등록됩니다.
- 생성 시점에 브랜드 중복, `STANDARD-v1` 누락·중복을 검사해 잘못된 구성이면 기동을 실패시킵니다.
- `resolve(brand)`: 매핑이 없으면 `STANDARD-v1`을 반환합니다.

### 3.3. `IngestService` 연동
- 중복 판정(IN-05)에서 기존 결과가 없고 공통 규칙(IN-06)을 통과하면 `registry.resolve(brand).validate(command)`를 실행합니다.
- 위반이 있으면 `BusinessException(POLICY_VIOLATION, errors, Map.of("policyCode", code))`를 던집니다. IN-02의 확장 속성 덕분에 응답 최상위에 `policyCode`가 나옵니다.

---

## 4. 작업 목록 (Tasks)

| 작업 | 대상 파일/클래스 | 작업 내용 |
|---|---|---|
| **TASK-01** | `policy/Violation`, `policy/ResultValidationPolicy` | • 위반 타입과 정책 인터페이스 |
| **TASK-02** | `policy/StandardPolicy`, `policy/BrandAPolicy` | • 정책 2종 구현 |
| **TASK-03** | `policy/PolicyRegistry` | • 브랜드 매핑, 기본값, 무결성 검사 |
| **TASK-04** | `ingest/IngestService#ingest` | • 정책 검증 단계와 `policyCode` 응답 |

---

## 5. 검증

- **`PolicyRegistryTest`** (단위)
  - `resolve("BRAND_A")` → `BRAND_A-v1`, `resolve("ZETA")` → `STANDARD-v1`
  - `swVersion`이 없는 ECU 2개 → 위반 2건, `field`가 `requestIndex` 기준
  - 브랜드 중복, `STANDARD-v1` 누락·중복 → `IllegalStateException`
- **`BrandPolicyIT`** (integrationTest)
  - `BRAND_A`, ECU `[VCU(swVersion 없음), BMS(있음), ABS(없음)]` POST → `422 policy-violation`, `policyCode = BRAND_A-v1`, `errors` field `{ecus[0].swVersion, ecus[2].swVersion}`, 4개 테이블 0행

```sh
./gradlew :gateway:test :gateway:integrationTest   # Windows: .\gradlew.bat ... (Docker 필요)
```

---


## 후속 구현 반영 (2026-11-07)

현재 수신 흐름은 형식 검증 → 정규화 → 중복 판정 → 공통 규칙 → 브랜드 정책 → 단일 트랜잭션 저장입니다. 신규 202 / 중복 200을 반환하며, 결과 ID로 조회할 수 있습니다. 성공 응답은 `data`로 래핑합니다. 상세 상태와 검증 기록은 [구현 현황](README.md)을 참조합니다.
