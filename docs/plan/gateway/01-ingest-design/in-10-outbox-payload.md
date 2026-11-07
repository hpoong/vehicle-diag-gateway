# IN-10 전송 본문 생성

> 문서 상태: 구현 완료 · 최신화: 2026-11-07  
> 구현 순서: 7 · 선행: IN-04

## 구현 동작

앱에서 UUID를 한 번 생성해 `outbox_event.event_id`와 `payload.eventId`에 동일하게 사용합니다.

- `source`: `system=diag-gateway`, `resultId`, `deviceId`, `deviceEventId`, `policyCode`
- 본문: `eventId`, `source`, `vin`, `brand`, `occurredAt`, `receivedAt`, `ecus`
- 시각: UTC 밀리초 3자리 `Z`, hash와 `UtcTime.MILLIS` 공유
- ECU: ID 오름차순, 없는 `swVersion` 키 생략, `sessionId`·`requestIndex` 제외
- 직렬화 실패는 unchecked 예외로 전달해 저장 전체 롤백
- DB 기본값: `PENDING`, `attempt_count=0`, `cycle_no=1`
- UUID는 문자열로 바인딩하고 SQL에서 `::uuid`로 변환합니다. DB 제약으로 payload ID 일치를 검사합니다.

실제 receiver HTTP 전송 워커는 이 수신 구현 범위에 포함되지 않습니다.

## 주요 구현

`OutboxPayloadFactory, OutboxDraft, OutboxMapper`

## 검증

- 테스트: `OutboxPayloadFactoryTest, IngestFlowIT`
- 명령: `./gradlew :gateway:test :gateway:integrationTest` (Java 17, Docker 필요)
- 실제 실행 결과: [구현 현황](README.md)의 검증 기록 참조.
