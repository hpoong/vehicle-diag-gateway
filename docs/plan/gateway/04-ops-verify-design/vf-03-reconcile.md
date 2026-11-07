# VF-03-RECONCILE 정합성 대조

## 구현

`ReconcileAssertions`는 통합 테스트에서만 두 DB를 읽는다. I1 결과/outbox 건수, I2 상태 합계, I3 SENT 수신 누락, I4 종료 시 집합 대조, I5 ECU·DTC 쌍 수, I6 최신 시각을 검사한다. I4의 예외는 허용 집합과 실제 DEAD의 교집합으로 제한한다. 비교 모드는 I3·I4·I5를 SKIP한다.

## 검증

`ReconcileAssertionsIT`: 정상 처리 외에 수신 누락·미허용 DEAD·잘못된 case_count·최신값 변조 및 삭제·outbox 누락을 만들어 검사기의 FAIL 판정을 확인한다. DUP1은 중복된 (event_id, ecu_id, dtc_code) 그룹 수다.
