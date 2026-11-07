# IN-03 요청 추적 로그

> 문서 상태: 구현 완료 · 최신화: 2026-11-07  
> 구현 순서: 11 · 선행: IN-01, 05~08, 11

## 구현 동작

기존 `RequestIdFilter`를 사용합니다. UUID 형식의 `X-Request-Id`만 재사용하며, 없거나 잘못된 값이면 UUID를 생성합니다. 응답 헤더와 오류 본문의 requestId는 같습니다.

| 판정 | INFO 로그 |
|---|---|
| 신규 | INGEST_ACCEPTED |
| 중복 | INGEST_DUPLICATE |
| 충돌 | INGEST_CONFLICT |
| 공통 위반 | INGEST_REJECTED(common-rule-violation) |
| 정책 위반 | INGEST_REJECTED(policy-violation) |
| JSON·형식 오류 | INGEST_REJECTED(invalid-request) |

MDC `requestId`, `deviceId`, `eventId`, `resultId`를 로그 패턴에 표시합니다. 컨트롤러는 finally에서 마지막 세 키를 제거합니다. 형식 오류는 메서드 진입 전 발생하므로 device/event ID가 없을 수 있습니다. payload나 DTC 값은 판정 로그에 넣지 않습니다. 400 전용 advice는 기존 오류 처리기에 응답 생성을 위임합니다.

## 주요 구현

`DiagnosticResultController, IngestService, IngestRejectionLogAdvice`

## 검증

- 테스트: `IngestFlowIT, IngestRequestValidationIT`
- 명령: `./gradlew :gateway:test :gateway:integrationTest` (Java 17, Docker 필요)
- 실제 실행 결과: [구현 현황](README.md)의 검증 기록 참조.
