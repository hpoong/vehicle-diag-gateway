# web-support: 공통 HTTP 기반

`web-support`는 실행 애플리케이션이 아닌 Java 라이브러리입니다. gateway와 receiver는 서로 의존하지 않고
각각 `implementation project(':web-support')`으로 공통 기반만 가져갑니다. DB, 업무 모델, 마이그레이션은 공유하지 않습니다.

## 성공 응답

```java
return ResponseEntity.accepted().body(ApiResponse.success(result));
// HTTP 202, {"data": { ... }}

return ResponseEntity.ok(ApiResponse.success());
// HTTP 200, {"data": null}
```

`ApiResponse`는 성공 전용이며 컨트롤러에서 명시적으로 사용합니다. 파일 다운로드, Actuator 응답,
`204 No Content` 등에는 자동으로 응답을 감싸지 않습니다.

## 에러 응답

```java
throw new BusinessException(CommonErrorCode.NOT_FOUND);

throw new BusinessException(GatewayErrorCode.POLICY_VIOLATION, List.of(
    new ApiFieldError("ecus[1].swVersion", "BRAND_A.SW_VERSION_REQUIRED", "swVersion이 필요합니다.")
));
```

에러는 Spring `ProblemDetail`과 `application/problem+json`을 사용합니다.

```json
{
  "type": "urn:diag-gateway:error:policy-violation",
  "title": "브랜드 정책을 위반했습니다.",
  "status": 422,
  "instance": "/api/v1/diagnostic-results",
  "errors": [{"field": "ecus[1].swVersion", "rule": "BRAND_A.SW_VERSION_REQUIRED", "message": "swVersion이 필요합니다."}],
  "requestId": "11823e8b-17c7-46b2-86f0-7bf3b96f9635"
}
```

서비스 전용 enum은 `ErrorCode`를 구현합니다. `GlobalExceptionHandler`는 업무 예외의 상태와 코드를 사용하고,
Bean Validation 오류는 `errors` 목록으로 반환합니다. 잘못된 JSON, 필수 파라미터 누락, 타입 불일치는 400입니다.
404, 405, 415 같은 Spring MVC 오류는 원래 상태와 관련 헤더를 유지합니다.
예상하지 못한 예외는 로그에 기록하고, 응답에는 내부 예외 메시지를 노출하지 않습니다.
이 처리 범위는 Spring MVC 컨트롤러입니다. 향후 Security 필터나 별도 서블릿 오류 처리를 추가하면 해당 계층에도 연결해야 합니다.

## 요청 추적

`X-Request-Id`가 UUID 형식이면 재사용하고 그 외에는 UUID를 생성합니다.
응답 헤더와 에러 본문의 `requestId`에 같은 값을 사용하며 처리 중 MDC `requestId`에 넣습니다.
요청 종료 시 기존 MDC 값을 복원합니다. 비동기 작업 실행 스레드로의 MDC 전파는 별도 설정이 필요합니다.

## receiver 추가

1. 루트 `settings.gradle`에 `include 'receiver'`를 추가합니다.
2. receiver를 독립 Spring Boot 애플리케이션으로 만들고 `implementation project(':web-support')`을 추가합니다.
3. Lombok `compileOnly`와 `annotationProcessor`를 gateway와 동일하게 설정합니다.
4. 포트, DB, Flyway 경로를 receiver 전용으로 설정합니다.
5. 아래 속성으로 receiver의 에러 type을 구분합니다.

```yaml
diag:
  web:
    problem-type-prefix: 'urn:diag-receiver:error:'
```

공통 필터와 Advice는 Boot 자동 설정으로 등록됩니다. 애플리케이션의 컴포넌트 스캔 범위를 넓힐 필요가 없습니다.
공통 패키지를 별도로 스캔하면 등록이 중복될 수 있으므로 기본 서비스 패키지 스캔을 유지합니다.
receiver의 업무 에러는 자신의 패키지에서 `ErrorCode` 구현으로 추가합니다.
gateway 디렉터리에서의 기존 Wrapper 명령도 계속 지원하며, 서비스가 추가된 이후에는 루트 빌드를 기준으로 사용합니다.

## 검증

저장소 루트에서 실행합니다. web-support의 HTTP 검증은 DB와 Docker 없이 수행합니다.

```powershell
.\gateway\gradlew.bat -p . :web-support:integrationTest :gateway:build
```

실제 DB 기동과 Flyway 검증은 기존 `:gateway:integrationTest`로 별도 실행합니다(Docker 필요).
