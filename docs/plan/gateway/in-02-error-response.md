# IN-02 공통 오류 응답 (RFC 9457) 구현 계획

> **문서 상태**: 준비 완료 (Ready)  
> **근거 설계**: 수신 API 오류 응답 규격 (RFC 9457)  
> **연관 작업**: 후행 작업(IN-05 충돌 409, IN-06 공통검증 422, IN-07 브랜드정책 422)의 공통 기반

---

## 1. 개요 및 목적

RFC 9457 기반의 기존 `GlobalExceptionHandler` 체계를 확장하여 다음 2가지 요구사항을 충족합니다.

1. **오류 응답 확장 속성(Properties) 지원**: 브랜드 정책 위반(`422`) 시 응답 본문 최상위에 `"policyCode": "<값>"`을 포함할 수 있도록 `BusinessException` 지원.
2. **DB 장애 시 503 매핑**: DB 연결 불가, 커넥션 풀 고갈 등으로 인한 장애 시 내부 예외 메시지를 은닉하고 `503 Service Unavailable`로 변환.

---

## 2. 요구사항 (Requirements)

| ID | 구분 | 요구 동작 및 수용 기준 |
|---|---|---|
| **REQ-01** | **표준 오류 응답 형식** | • `Content-Type: application/problem+json`<br/>• `type = urn:diag-gateway:error:<code>`<br/>• `requestId` = 응답 헤더 `X-Request-Id`와 일치<br/>• 세부 필드 검증 실패 목록(`errors[]`) 포함 |
| **REQ-02** | **확장 속성 (Custom Properties)** | • `BusinessException`에 추가 속성 전달 시 응답 최상위에 key-value로 노출 (예: `"policyCode": "BRAND_A-v1"`)<br/>• 예약된 기본 키(`type`, `title`, `status`, `detail`, `instance`, `errors`, `requestId`) 전달 시 `IllegalArgumentException` 발생 |
| **REQ-03** | **DB 장애 503 변환** | • DB 커넥션 획득 실패, 트랜잭션 시작 실패 시 `503`, `type = urn:diag-gateway:error:service-unavailable`<br/>• DB 연결 정보나 내부 스택트레이스는 응답에 절대 노출하지 않음 |

---

## 3. 변경 설계

### 3.1. `web-support` 모듈 수정 (공통 예외 확장)
- **`BusinessException`**:
  - `Map<String, Object> properties` 필드 추가
  - 3-인자 생성자 `(ErrorCode, List<ApiFieldError>, Map<String, Object>)` 추가
  - 생성자에서 예약 키(`type`, `title`, `status` 등) 충돌 검사
- **`GlobalExceptionHandler`**:
  - `handleBusinessException`에서 `properties` 맵의 항목들을 `ProblemDetail.setProperty()`로 복사

### 3.2. `gateway` 모듈 수정 (DB 예외 503 처리)
- `web-support`는 DB 의존성이 없으므로, gateway 전용 Advice를 추가합니다.
- **`GatewayExceptionAdvice`** (`@RestControllerAdvice`, `@Order(Ordered.HIGHEST_PRECEDENCE)`):
  - 대상 예외: `DataAccessResourceFailureException`, `TransientDataAccessException`, `CannotCreateTransactionException`, MyBatis 래핑 예외(`CannotGetJdbcConnectionException` 체인)
  - 처리: WARN 로그 기록 후 `GlobalExceptionHandler`에 `CommonErrorCode.SERVICE_UNAVAILABLE` 위임

---

## 4. 작업 목록 (Tasks)

| 작업 | 대상 파일/클래스 | 작업 내용 |
|---|---|---|
| **TASK-01** | `web-support/.../BusinessException.java` | • `properties` 필드 및 생성자 추가<br/>• 예약 키 검증 로직 추가 |
| **TASK-02** | `web-support/.../GlobalExceptionHandler.java` | • `properties` 항목을 ProblemDetail에 바인딩 |
| **TASK-03** | `gateway/.../common/error/GatewayExceptionAdvice.java` | • DB 장애 예외 핸들러 작성 (503 변환) |
| **TASK-04** | `gateway/src/integrationTest/.../ErrorProbeController.java` | • 예외 검증용 테스트 컨트롤러 및 통합 테스트(`ErrorResponseIT`) 작성 |

---

## 5. 검증 계획

- **TEST-01 (기본 오류 규격)**: `EVENT_CONFLICT`(409), `COMMON_RULE_VIOLATION`(422), `NOT_FOUND`(404) 발생 시 RFC 9457 형식 및 `requestId` 일치 확인.
- **TEST-02 (확장 속성)**: `policyCode` 속성이 ProblemDetail 본문에 정상 포함되는지 및 예약 키 예외 발생 확인.
- **TEST-03 (DB 장애)**: `CannotCreateTransactionException` 및 MyBatis 커넥션 실패 시 `503 Service Unavailable` 변환 및 내부 메시지 은닉 확인.

```powershell
# 검증 실행 명령 - Windows (PowerShell)
.\gradlew.bat :web-support:integrationTest
.\gradlew.bat :gateway:integrationTest
```

```sh
# 검증 실행 명령 - macOS (zsh)
./gradlew :web-support:integrationTest
./gradlew :gateway:integrationTest
```

