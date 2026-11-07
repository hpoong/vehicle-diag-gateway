# OP-03-ATTEMPT-HISTORY 전송 시도 이력

## 구현

`GET /ops/events/{outboxId}/attempts`는 id 오름차순으로 cycleNo·attemptNo·workerId·outcome·httpStatus·errorCode·latencyMs를 반환한다. 시도 없는 outbox는 빈 배열, 없는 outbox는 404다.

## 검증

`OpsAttemptsIT`: RETRYABLE→ABANDONED→SUCCESS, 다른 outbox 격리, 빈 목록과 잘못된 ID를 확인한다. 재처리 전후 사이클은 `OpsRequeueIT`에서 확인한다.
