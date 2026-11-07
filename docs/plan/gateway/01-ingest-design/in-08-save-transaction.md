# IN-08 저장 트랜잭션

> 문서 상태: 구현 완료 · 최신화: 2026-11-07  
> 구현 순서: 8 · 선행: IN-04~07, 10

## 구현 동작

`IngestService`와 별도 빈인 `IngestWriter.save`에 `@Transactional(rollbackFor = Exception.class)`을 적용했습니다.

1. `diagnostic_result`: 키 충돌 시 `DO NOTHING`, 성공 시 ID·DB 수신 시각 반환
2. `ecu_observation`: 정렬한 관측 저장, DTC는 `String[]` + `ArrayTypeHandler`
3. `vehicle_ecu_latest`: 정렬된 ECU 순서로 조건부 UPSERT
4. `outbox_event`: 전송 본문과 UUID 저장

어느 단계든 예외가 발생하면 네 테이블의 변경을 모두 롤백합니다. INSERT RETURNING은 MyBatis `affectData=true`와 `flushCache=true`로 쓰기 동작임을 지정합니다.

키 충돌로 0행이면 writer 트랜잭션을 종료한 후 기존 결과를 다시 조회해 동일 hash는 DUPLICATE, 다른 hash는 409로 판정합니다. 같은 요청을 동시에 5번 보내도 신규 1개만 저장됩니다. 원본 JSON 파싱 결과·hash·적용 정책 코드를 함께 저장합니다.

## 주요 구현

`IngestWriter, DiagnosticResultMapper, IngestService`

## 검증

- 테스트: `IngestFlowIT, IngestRollbackIT`
- 명령: `./gradlew :gateway:test :gateway:integrationTest` (Java 17, Docker 필요)
- 실제 실행 결과: [구현 현황](README.md)의 검증 기록 참조.
