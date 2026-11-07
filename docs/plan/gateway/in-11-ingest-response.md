# IN-11 수신 응답

> 문서 상태: 구현 완료 · 최신화: 2026-11-07  
> 구현 순서: 10 · 선행: IN-01, 05, 08

## 구현 동작

성공 응답은 프로젝트 공통 `ApiResponse`에 따라 `{"data": ...}`로 래핑합니다.

| 상황 | HTTP | data.status |
|---|---|---|
| 신규 저장 완료 | 202 | ACCEPTED |
| 같은 키·같은 hash 재전송 | 200 | DUPLICATE |
| 같은 키·다른 hash | 409 | 오류 본문 |

`data`에는 `resultId`, `deviceId`, `eventId`, `status`, `policyCode`, `receivedAt`, `delivery{eventId,status}`가 있습니다. 수신 시각은 밀리초로 절삭한 UTC 문자열입니다. 중복 응답은 기존 결과 ID·전송 UUID를 유지하며 전송 상태는 조회 시점 DB 값입니다.

202는 DB 접수 및 전송 예약 완료를 뜻합니다. receiver 전송 완료를 뜻하지 않습니다.

## 주요 구현

`IngestResponse, DiagnosticResultController`

## 검증

- 테스트: `IngestFlowIT, IngestRequestValidationIT`
- 명령: `./gradlew :gateway:test :gateway:integrationTest` (Java 17, Docker 필요)
- 실제 실행 결과: [구현 현황](README.md)의 검증 기록 참조.
