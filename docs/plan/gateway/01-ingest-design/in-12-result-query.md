# IN-12 결과 조회 API

> 문서 상태: 구현 완료 · 최신화: 2026-11-07  
> 구현 순서: 12 · 선행: IN-02, 08, 10, 11

## 구현 동작

`GET /api/v1/diagnostic-results/{resultId}`

- 정상: 200, `data`에 결과 메타데이터와 ECU 관측·현재 전송 상태 반환
- ECU는 `ecuId` 오름차순, DTC는 저장된 정렬 순서
- `delivery`: `eventId`, `status`, `attemptCount`, `lastOutcome`, `sentAt`
- 초기 상태: PENDING, 시도 0, lastOutcome·sentAt null
- 전송 상태를 DB에서 바꾸면 다음 조회에 반영
- 없는 숫자 ID: 404 not-found, 숫자가 아닌 ID: 400 invalid-request
- 결과가 존재하는데 outbox가 없으면 저장 불변식 위반으로 500 처리

결과와 관측은 저장 후 불변이며, 조회는 별도의 쓰기 트랜잭션 없이 세 SELECT로 조립합니다.

## 주요 구현

`DiagnosticResultQueryService, DiagnosticResultResponse, DiagnosticResultController`

## 검증

- 테스트: `IngestFlowIT`
- 명령: `./gradlew :gateway:test :gateway:integrationTest` (Java 17, Docker 필요)
- 실제 실행 결과: [구현 현황](README.md)의 검증 기록 참조.
