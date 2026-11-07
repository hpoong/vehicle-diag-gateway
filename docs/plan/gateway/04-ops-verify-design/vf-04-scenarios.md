# VF-04-SCENARIOS 장애 시나리오

## 구현

IngestScenarioIT(S1~S3), DeliveryScenarioIT(S4·S5·S7~S9), RequeueScenarioIT(S10), NaiveCompareScenarioIT(S12)로 실제 receiver를 호출한다. 클래스마다 receiver를 소유하고 DB를 초기화하며, 스케줄러 대신 runOnce를 직접 실행한다. read-timeout=500ms, 응답 유실 hold=1500ms, batch-size=20, max-attempts=3이다.

## 검증

기본 모드는 결과·SENT·수신 건수 일치, PENDING/DEAD/DUP1=0과 I1~I6 판정을 확인한다. 비교 모드는 중복 탐지를 확인한다. 결과는 SLF4J의 SCENARIO_RESULT 로그로 XML에 남긴다.
