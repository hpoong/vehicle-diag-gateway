# OP-02-EVENT-LIST 운영 이벤트 목록

## 구현

`GET /ops/events?status=DEAD&limit=50`. status는 PENDING/SENT/DEAD 중 필수이며 대소문자를 구분한다. limit 기본 50, 범위 1~200, 잘못된 값은 400이다. PENDING은 next_attempt_at 오름차순, SENT/DEAD는 각각 sent_at/dead_at 내림차순이며 동률은 id 오름차순이다. payload·VIN·claimToken은 포함하지 않는다.

## 검증

`OpsEventsIT`: 상태 필터, 세 상태 정렬, 제한·입력 검증, 민감 필드 제외, 요약과 목록의 건수 일치를 확인한다. 상태 조건은 XML include의 정적 상수로만 치환하여 부분 인덱스 조건을 유지한다.
