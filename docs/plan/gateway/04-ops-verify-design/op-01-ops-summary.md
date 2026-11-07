# OP-01-OPS-SUMMARY 운영 요약 API

## 구현

`GET /ops/summary`는 단일 SQL로 PENDING/SENT/DEAD/TOTAL, DEAD 사유별 건수, 가장 오래된 PENDING 생성 후 경과 초를 반환한다. PENDING이 없으면 대기 시간은 null이다. asOf는 DB now()의 밀리초 절삭 UTC 시각이다.

## 검증

`OpsSummaryIT`: 상태별 집계, 빈 집계, 600초 대기, DB 기준 시각, HTTP 405 응답을 확인한다. 호스트와 Docker VM 시계 차이를 피하려고 asOf를 DB에서 읽은 전후 시각과 비교한다.
