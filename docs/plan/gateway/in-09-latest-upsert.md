# IN-09 최신 상태 갱신

> 문서 상태: 구현 완료 · 최신화: 2026-11-07  
> 구현 순서: 9 · 선행: IN-08

## 구현 동작

최신 상태는 `(vin, ecu_id)`를 키로 관리합니다. 기존 값의 `(occurred_at, result_id)`보다 새 값의 튜플이 클 때만 갱신합니다.

- 늦게 도착한 과거 결과도 이력·outbox에는 저장하지만 최신값은 유지합니다.
- 진단 시각이 같으면 더 큰 결과 ID가 남습니다.
- 빈 DTC 배열도 유효한 관측이며 최신 상태를 빈 배열로 갱신합니다.
- ECU ID 오름차순으로 한 행씩 처리해 잠금 순서를 통일합니다.
- 갱신 0행은 정상입니다. 오류로 처리하지 않습니다.
- outbox 실패 시 최신 상태 변경도 함께 롤백합니다.

## 주요 구현

`DiagnosticResultMapper.upsertLatest, IngestWriter`

## 검증

- 테스트: `IngestFlowIT, IngestRollbackIT`
- 명령: `./gradlew :gateway:test :gateway:integrationTest` (Java 17, Docker 필요)
- 실제 실행 결과: [구현 현황](README.md)의 검증 기록 참조.
