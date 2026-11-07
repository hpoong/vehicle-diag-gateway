# IN-06 공통 규칙 검증

> 문서 상태: 구현 완료 · 최신화: 2026-11-07  
> 구현 순서: 6 · 선행: IN-04, 05, 07

## 구현 동작

중복 판정 다음, 브랜드 정책 전에 실행합니다. 공통 규칙은 정책으로 우회할 수 없습니다.

| 규칙 | 조건 | 오류 필드 |
|---|---|---|
| C1 | ECU가 0개 | `ecus` |
| C2 | 진단 시각이 앱 현재 시각 + 5분 초과 | `occurredAt` |
| C3 | 동일 ECU ID가 두 번 이상 등장 | `ecus[요청 위치].ecuId` |
| C4 | 한 ECU 안에 같은 DTC 반복 | `ecus[요청 위치].dtcCodes` |

모든 공통 위반을 모아 `422 common-rule-violation`으로 반환합니다. 정확히 +5분은 통과하며 과거 시각에는 하한이 없습니다. ECU 정렬 후에도 원래 요청 위치를 유지합니다.

## 주요 구현

`CommonRuleValidator`

## 검증

- 테스트: `CommonRuleValidatorTest, IngestFlowIT`
- 명령: `./gradlew :gateway:test :gateway:integrationTest` (Java 17, Docker 필요)
- 실제 실행 결과: [구현 현황](README.md)의 검증 기록 참조.
