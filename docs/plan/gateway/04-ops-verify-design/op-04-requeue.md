# OP-04-REQUEUE 수동 재처리

## 구현

`POST /ops/events/{outboxId}/requeue`는 X-Operator(필수, 공백 불가, 최대 64자), JSON reason(10~500 Unicode code point, 공백만·NUL·비문자열 불가)을 받는다. DEAD만 PENDING으로 변경하며 attempt_count=0, cycle_no+1, next_attempt_at=now(), DEAD·선점 필드를 초기화한다. 이전 실패 요약과 시도 이력은 보존한다.

## 검증

조건부 행 잠금·UPDATE와 ops_action_log INSERT를 같은 트랜잭션으로 처리한다. 없는 ID는 404, 다른 상태나 중복·동시 패자는 409다. 감사 기록에는 요청 ID를 저장한다. `OpsRequeueIT`와 `OpsRequeueRollbackIT`에서 동시 요청 1회 성공·감사 실패 전체 롤백·다음 사이클 전송·Unicode 경계를 확인한다.
