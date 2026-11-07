# DL-06 백오프 계산 구현 계획

> 문서 상태: 구현 계획 · 문서 정리: 2026-11-07  
> 원본: [계획 자료](../../../../agent/plan-agent/out/02-delivery-retry-design/dl-06-backoff/plan.md) · [전체 목차](README.md)  
> 아래 FACT와 BASELINE은 원본 계획 수립 시점의 기록입니다. READY는 계획 준비 상태이며 구현 완료나 테스트 통과를 뜻하지 않습니다.

PLAN_STATUS: READY — 공식·상한·지터 방식(02 §6) 확정
BLOCKER: NONE

근거: `temp/설계/02_전송_재처리_설계.md` §6 · 구현 순서: **2번째** · 선행: DL-01(`DeliveryProperties.Backoff`) · 후행: DL-07(재시도 예약) · BASELINE: main @ 2125a48

## 1. 요약

실패한 시도 번호 `n`으로 다음 시도까지의 지연을 계산하는 순수 계산 컴포넌트 `BackoffPolicy`를 만듭니다.
`raw(n) = min(base × multiplier^(n−1), max)`, `delay(n) = raw/2 + random(0, raw/2)` (equal jitter).
난수는 주입할 수 있게 분리해 D11을 고정 난수로 검증합니다. DB·HTTP와 무관합니다.

## 2. 요구사항

| REQ | 요구 동작 | 수용 기준 |
|---|---|---|
| REQ-01 | 지수 증가·상한 | 기본값(5s, 2, 5m)에서 난수 최소일 때 `delay(1)=2.5s`, `delay(2)=5s`, `delay(3)=10s`, `delay(7)=delay(9)=150s`. 난수 최대일 때 `5s`, `10s`, `20s`, `300s` |
| REQ-02 | 지터 범위 | 실제 난수로 `delay(n)`을 1,000번 계산하면 모두 `[raw/2, raw]` 안에 있습니다 |
| REQ-03 | 경계 | `base=0s`이면 모든 `n`에서 `Duration.ZERO`. `n=1000`이어도 오버플로 없이 `≤ max`. `n ≤ 0`이면 `IllegalArgumentException` |

## 3. 계획 수립 당시 구조 (FACT)

- 백오프·난수 관련 코드 없음 (`gateway/src/main/java/com/hopoong/diag/gateway/delivery`에는 `OutboxMapper`·`package-info`만 있음).
- 단위 테스트는 `gateway/src/test`(예: `policy/PolicyRegistryTest`), 명령은 `:gateway:test`.

## 4. 변경 설계 (PROPOSED)

- `delivery/BackoffPolicy` (`@Component`)
  - 공개 생성자 `BackoffPolicy(DeliveryProperties p)` → 패키지 생성자 `BackoffPolicy(DeliveryProperties.Backoff b, LongUnaryOperator randomUpToInclusive)`에 위임합니다.
  - 운영 난수: `bound -> ThreadLocalRandom.current().nextLong(bound + 1)`. DL-02에서 여러 전송 스레드가 동시에 호출하므로 공유 `Random` 인스턴스를 쓰지 않습니다.
  - `Duration delay(int failedAttempt)`
    ```text
    rawMs  = (long) Math.min(baseMs * Math.pow(multiplier, n - 1), maxMs)   // double 계산이라 큰 n도 Infinity → max로 잘림
    halfMs = rawMs / 2
    delay  = halfMs + random(0..halfMs)
    ```
  - `rawMs = 0`이면 `nextLong(1)`이 0을 돌려주므로 `nextLong(0)` 예외가 생기지 않습니다.
- 인자 `n`은 "방금 실패한 시도 번호" = 선점 시점의 `attempt_count`입니다(DL-07에서 `ClaimedEvent.attemptCount()`를 넘깁니다).

## 5. 작업

| TASK | REQ | 대상 (경로+심볼) | 변경 내용 | 완료 조건 |
|---|---|---|---|---|
| TASK-01 | REQ-01~03 | 신규 `delivery/BackoffPolicy#delay` | 4절 공식, 난수 주입 생성자 | TEST-01~04 |

## 6. 테스트

| TEST | REQ | 조건·입력 | 기대 결과 | 종류 |
|---|---|---|---|---|
| TEST-01 | REQ-01 | D11: 난수 `b -> 0`, n = 1, 2, 3, 6, 7, 9 | 2.5s, 5s, 10s, 80s, 150s, 150s | 단위 (`BackoffPolicyTest`) |
| TEST-02 | REQ-01 | D11: 난수 `b -> b`, 같은 n | 5s, 10s, 20s, 160s, 300s, 300s | 단위 |
| TEST-03 | REQ-02 | 운영 생성자, n = 1..10 각 100회 | 모두 `[raw/2, raw]` | 단위 |
| TEST-04 | REQ-03 | `base=0s` n=5 / n=1000 / n=0 | `ZERO` / `≤ 5m` / `IllegalArgumentException` | 단위 |

명령: `./gradlew :gateway:test`
