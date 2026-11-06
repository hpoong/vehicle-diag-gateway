# 개발 컨벤션 가이드 (Conventions)

> **문서 상태**: v1.0 (2026-11-06)  
> **적용 대상**: `vehicle-diag-gateway` 전체 프로젝트 (`gateway`, `receiver`)

이 문서는 프로젝트 개발 시 일관성 있는 코드 품질과 유지보수성을 확보하기 위해 준수해야 하는 공통 규칙과 컨벤션을 정의합니다.

---

## 1. 패키지 및 모듈 구조 원칙

### 1.1. 패키지 명명 및 책임 분리
`com.hopoong.diag.gateway` 하위에 기능(책임) 단위로 패키지를 구성합니다. 계층형(`controller`, `service`)보다 **기능형(Feature-driven)** 패키지 구조를 우선합니다.

| 패키지 | 책임 및 역할 | 포함되는 주요 클래스 |
|---|---|---|
| `gateway.common` | gateway 전용 기반 코드와 업무 에러 코드 | `GatewayErrorCode` |
| `com.hopoong.diag.websupport` (별도 `web-support` 모듈) | 서비스 간 공유하는 HTTP 응답, RFC 9457 예외 처리, 요청 추적 | `ApiResponse`, `GlobalExceptionHandler`, `RequestIdFilter` |
| `ingest` | 진단 결과 수신 API, 중복 판별, 원본 저장 | `DiagnosticResultController`, `IngestService`, `DiagnosticResultMapper` |
| `policy` | 브랜드별/공통 검증 정책 엔진 | `ResultValidationPolicy`, `BrandAPolicy`, `StandardPolicy` |
| `delivery` | Outbox 테이블 선점, 백오프 재시도, 외부 전송 | `DeliveryScheduler`, `DeliveryService`, `OutboxMapper`, `ReceiverClient` |
| `ops` | 운영자 모니터링 조회, 수동 재처리 API | `OpsSummaryController`, `OpsService`, `OpsMapper` |

### 1.2. 네이밍 규칙 (Java / Spring)

- **클래스 네이밍**:
  - 컨트롤러: `*Controller` (예: `IngestController`, `OpsController`)
  - 비즈니스 로직 / 서비스: `*Service` 또는 `*UseCase` (예: `IngestService`, `OutboxRelayService`)
  - DB 매퍼: `*Mapper` (MyBatis 인터페이스)
  - 요청 DTO / 커맨드: `*Request` 또는 `*Command` (Java `record` 권장)
  - 응답 DTO: `*Response` (Java `record` 권장)
  - 엔티티/도메인 모델: 명사형 단수 (예: `DiagnosticResult`, `OutboxEvent`)
  - 정책 구현체: `*Policy` (예: `BrandAPolicy`, `StandardPolicy`)
- **메서드 네이밍**:
  - 행위 중심의 동사 사용: `ingest(...)`, `claimPendingEvents(...)`, `requeue(...)`, `getSummary(...)`
  - 단순 조회의 경우 `findBy...`, `get...` 사용

---

## 2. Java 17 코딩 컨벤션

### 2.1. 불변 객체와 Record 활용
- DTO, Value Object, Command 객체는 Lombok의 `@Getter`, `@RequiredArgsConstructor` 또는 `@AllArgsConstructor`로 불변 객체를 구성합니다. 필요한 경우 Java 17 `record`도 사용할 수 있습니다.
- 생성자 주입은 Lombok `@RequiredArgsConstructor`, 로깅은 `@Slf4j`를 사용합니다. 불필요한 setter와 `@Data`는 피합니다.
- `final` 키워드를 적극적으로 활용하여 변수 및 파라미터의 재할당을 방지합니다.

```java
// Good: record를 활용한 불변 Command
public record IngestDiagnosticResultCommand(
    String deviceId,
    String eventId,
    String vin,
    String brand,
    Instant occurredAt,
    List<EcuObservationDto> ecuObservations,
    Map<String, Object> rawPayload
) {}
```

### 2.2. 의존성 주입 (DI)
- 필드 주입(`@Autowired`)을 **엄격히 금지**하며, **생성자 주입(Constructor Injection)**을 사용합니다.
- 필드는 `private final`로 선언합니다.

```java
@Service
public class IngestService {
    private final DiagnosticResultMapper resultMapper;
    private final OutboxMapper outboxMapper;
    private final ValidationPolicyRegistry policyRegistry;

    public IngestService(
        DiagnosticResultMapper resultMapper,
        OutboxMapper outboxMapper,
        ValidationPolicyRegistry policyRegistry
    ) {
        this.resultMapper = resultMapper;
        this.outboxMapper = outboxMapper;
        this.policyRegistry = policyRegistry;
    }
}
```

---

## 3. REST API & 에러 처리 컨벤션

### 3.1. 엔드포인트 URL 규칙
- 복수형 명사를 기본으로 사용하며, 소문자 케밥 케이스(`kebab-case`)를 적용합니다.
- CRUD 행위를 나타내는 동사를 URL에 넣지 않고 HTTP Method로 표현합니다.
  - `POST /api/v1/diagnostic-results` (수신 접수)
  - `GET /api/v1/diagnostic-results/{resultId}` (단건 조회)
  - `GET /ops/summary` (운영 요약)
  - `POST /ops/events/{outboxId}/requeue` (수동 상태 변경과 같은 특수 행위는 동사 허용)

### 3.2. HTTP 상태 코드 규약

| 상태 코드 | 사용 상황 | 설명 |
|---|---|---|
| `200 OK` | 정상 조회, 또는 **단말 재전송** 처리 시 | 동일 `(device_id, event_id)` + 동일 hash 재전송 시 기존 결과 ID를 반환하며 `200` 응답 |
| `202 Accepted` | 수신 저장 완료 | 진단 결과 저장 및 Outbox 등록 완료 (외부 전송은 비동기 예정) |
| `400 Bad Request` | 필수 필드 누락, 포맷 오류 | Bean Validation 실패, JSON 역직렬화 실패, 정의되지 않은 필드, `X-Operator` 누락 |
| `404 Not Found` | 조회·재처리 대상 없음 | |
| `409 Conflict` | 데이터 충돌, 허용되지 않는 상태 전이 | 동일 `(device_id, event_id)`인데 `payload_hash`가 다른 경우, `DEAD`가 아닌 건 requeue |
| `422 Unprocessable Content` | 비즈니스 규칙/정책 위반 | 공통 규칙(C1~C4) 또는 브랜드 정책 위반 |
| `500 Internal Server Error` | 시스템 오류 | 처리되지 않은 예외 |
| `503 Service Unavailable` | DB 장애 등 일시 오류 | 단말은 같은 `eventId`로 재전송하면 된다 |

운영 API 인증(`X-Ops-Key`, `401`)은 5일 범위에서 제외했다.

### 3.3. 표준 에러 응답 규격 (RFC 9457 Problem Details)
성공 응답은 `web-support` 모듈의 `ApiResponse<T>`를 사용해 `{"data": ...}` 형태로 반환합니다.
HTTP 상태는 컨트롤러의 `ResponseEntity`로 지정합니다. `204 No Content`는 본문 없이 반환합니다.
공통 HTTP 기반은 `com.hopoong.diag.websupport`에, gateway 전용 에러 코드는 `gateway.common.error`에 둡니다.

모든 에러 응답 본문은 Content-Type `application/problem+json`을 준수합니다.

```json
{
  "type": "urn:diag-gateway:error:policy-violation",
  "title": "브랜드 정책 위반",
  "status": 422,
  "instance": "/api/v1/diagnostic-results",
  "requestId": "0d6a...",
  "policyCode": "BRAND_A-v1",
  "errors": [
    {
      "field": "ecus[1].swVersion",
      "rule": "BRAND_A.SW_VERSION_REQUIRED",
      "message": "BRAND_A는 모든 ECU에 swVersion이 필요합니다"
    }
  ]
}
```

- `type`은 `urn:diag-gateway:error:<code>`, code는 kebab-case (`invalid-request`, `event-conflict`, `common-rule-violation`, `policy-violation`, `invalid-state-transition`, `not-found`, `service-unavailable`).
- `errors[]`에는 위반 항목을 모두 담는다. 첫 위반에서 멈추지 않는다.

### 3.4. 공통 HTTP 헤더
- `X-Request-Id`: 모든 요청과 응답에 포함되는 추적용 UUID. (로그 MDC에 자동 연계)
- `X-Operator`: 수동 재처리 수행 시 작업자 식별자 (감사 로그용).

---

## 4. 데이터베이스 및 SQL 컨벤션 (PostgreSQL & MyBatis)

### 4.1. 명명 규칙
- **테이블/컬럼명**: `snake_case`, 단수형 명사 사용 (예: `diagnostic_result`, `outbox_event`)
- **기본키(PK)**: `id` (`bigserial`), 시스템 간 식별자는 `event_id` (`uuid` 또는 `varchar`)
- **시각 컬럼**: 타임존이 포함된 `timestamptz` 필수 사용, 컬럼명은 `_at` 접미사 (`occurred_at`, `received_at`, `created_at`, `sent_at`)
- **제약 조건(Constraint) 접두사**:
  - Primary Key: `pk_{테이블명}` (생략 시 기본)
  - Unique: `uq_{테이블명}_{컬럼들}` (예: `uq_result_device_event`)
  - Foreign Key: `fk_{자식테이블}_{부모테이블}`
  - Check: `ck_{테이블명}_{컬럼}` (예: `ck_observation_dtc_count`)
  - Index: `ix_{테이블명}_{컬럼들}` (예: `ix_result_vin_occurred`, `ix_outbox_pending`)

### 4.2. MyBatis SQL 작성 수칙
- SQL 키워드는 **대문자**(`SELECT`, `INSERT`, `UPDATE`, `FROM`, `WHERE`, `ORDER BY` 등)로 작성합니다.
- 파라미터 바인딩은 항상 `#{param}`을 사용하며, SQL 인젝션 위험이 있는 `${param}`은 사용을 금지합니다.
- 복수 행 선점 시 반드시 `FOR UPDATE SKIP LOCKED`를 사용합니다.
- 충돌 제어 시 `ON CONFLICT (...) DO NOTHING` 또는 `ON CONFLICT (...) DO UPDATE`를 명시합니다.

```sql
-- Good: outbox 선점 쿼리 예시 (claim_token은 행마다 새로 발급)
WITH picked AS (
    SELECT id
      FROM outbox_event
     WHERE status = 'PENDING'
       AND next_attempt_at <= now()
       AND (locked_until IS NULL OR locked_until < now())
     ORDER BY next_attempt_at, id
     LIMIT #{batchSize}
       FOR UPDATE SKIP LOCKED
)
UPDATE outbox_event o
   SET claim_token   = gen_random_uuid(),
       locked_by     = #{instanceId},
       locked_until  = now() + #{leaseSeconds} * INTERVAL '1 second',
       attempt_count = o.attempt_count + 1,
       updated_at    = now()
  FROM picked p
 WHERE o.id = p.id
RETURNING o.id, o.event_id, o.payload, o.attempt_count, o.cycle_no, o.claim_token;
```

- 선점 토큰을 배치 전체에 하나로 주지 않는다. `delivery_attempt.claim_token`이 고유 제약이고, 행마다 토큰이 달라야 늦게 끝난 결과를 걸러낼 수 있다.
- 선점 이후 결과 기록 UPDATE는 항상 `WHERE id = #{id} AND claim_token = #{claimToken} AND status = 'PENDING'` 조건을 둔다. 영향 행 0이면 `STALE_CLAIM`이다.
- HTTP 호출은 트랜잭션 밖에서 한다. `@Transactional` 메서드 안에서 `ReceiverClient`를 호출하지 않는다.

### 4.3. Flyway 마이그레이션 규칙
- 파일 위치: `gateway/src/main/resources/db/migration/`, `receiver/src/main/resources/db/migration/` (서비스별로 독립)
- 파일 네이밍: `V{순번}__{간단한_설명}.sql` (예: `V1__ingest.sql`, `V2__outbox.sql`)
- **절대 규칙**: 이미 배포되거나 공유된 마이그레이션 파일은 수정하지 않고, 항상 새로운 버전 파일(`V4__...sql`)을 추가하여 변경합니다.

---

## 5. 로깅 및 추적 컨벤션 (MDC)

### 5.1. 로깅 원칙
- `System.out.println` 사용 금지, 반드시 SLF4J `Logger`를 사용합니다.
- 대용량 원본 payload 전체를 `INFO` 레벨로 남기지 않습니다. (식별자와 요약 정보만 로깅)
- 중요한 비즈니스 상태 전이(`PENDING -> SENT`, `PENDING -> DEAD`)는 `INFO` 레벨로 명확히 기록합니다.

### 5.2. MDC (Mapped Diagnostic Context) 키 규약
모든 트랜잭션 및 비동기 워커 실행 시 아래 MDC 키를 컨텍스트에 설정하여 로그 추적성을 보장합니다.

| MDC Key | 설명 | 설정 위치 |
|---|---|---|
| `requestId` | HTTP 요청 추적 ID | Filter / Interceptor |
| `deviceId` | 진단 단말기 ID | 수신 Controller |
| `eventId` | 결과/이벤트 고유 식별자 | 수신 서비스, 전송 워커 |
| `claimToken` | 전송기 선점 토큰 | Outbox 워커 |

---

## 6. 테스트 작성 컨벤션 (Testcontainers & JUnit 5)

### 6.1. 테스트 원칙
- **H2 임베디드 DB 사용 금지**: `SKIP LOCKED`, `ON CONFLICT`, `jsonb`, 부분 인덱스 등 PostgreSQL 전용 기능을 테스트해야 하므로 **Testcontainers(PostgreSQL 16)**를 필수로 사용합니다.
- 단위 테스트(Unit Test)와 통합 테스트(Integration Test)를 명확히 구분합니다. 정책·백오프 계산은 단위 테스트, DB·HTTP가 걸리면 `integrationTest`입니다.
- 시나리오 테스트는 gateway `integrationTest`에서 receiver를 같은 JVM(`SpringApplicationBuilder`, 랜덤 포트, `chaos` 프로필)으로 띄웁니다.
- 전송기는 `gateway.delivery.enabled=false`로 끄고 테스트가 `DeliveryService.runOnce()`를 직접 호출합니다. `Thread.sleep`으로 스케줄러를 기다리지 않습니다. 백오프 대기는 `next_attempt_at`을 당겨서 건너뜁니다.
- 시나리오가 끝나면 `ReconcileAssertions`로 불변식 I1~I6과 중복 반영 수를 확인합니다.

### 6.2. 테스트 메서드 명명 규약
`Given-When-Then`을 메서드 이름이나 `@DisplayName`에 명확하게 표현합니다.

```java
@Test
@DisplayName("동일한 deviceId와 eventId로 다른 payload 전송 시 409 Conflict를 반환한다")
void shouldReturn409_whenDuplicateKeyWithDifferentPayload() {
    // given
    ...
    // when
    ...
    // then
    ...
}
```

---

## 7. Git & 커밋 컨벤션

### 7.1. 커밋 메시지 구조
```text
<type>(<scope>): <subject>

[선택적 본문(body): 변경 이유 및 주요 상세 내용]

[선택적 꼬리말(footer): 이슈 트래커 번호 등]
```

### 7.2. Commit Type 규칙
- `feat`: 새로운 기능 추가 (API 엔드포인트, 검증 정책 등)
- `fix`: 버그 수정
- `refactor`: 프로덕션 코드 리팩토링 (기능 변화 없음)
- `test`: 테스트 코드 추가 및 수정
- `docs`: 문서 작성 및 수정 (`docs/`, README.md 등)
- `chore`: 빌드 스크립트 수정, 패키지 매니저 설정, 의존성 업데이트 등
- `style`: 코드 포맷팅, 세미콜론 누락 등 (비즈니스 로직 영향 없음)

### 7.3. 예시
```text
feat(ingest): 진단 결과 중복 수신 판별 및 SHA-256 해시 검증 구현

- 동일 (device_id, event_id) 존재 시 hash 비교 후 200 또는 409 반환
- RFC 9457 ProblemDetails 예외 처리 연동
```

