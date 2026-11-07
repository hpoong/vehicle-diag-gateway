# simulator: 가상 진단 단말

실제로 실행 중인 gateway의 `POST /api/v1/diagnostic-results`에 가상 진단 결과를 보냅니다.
재전송, 같은 키·다른 내용, 늦게 도착한 과거 결과, 브랜드 정책 위반을 섞어 보낼 수 있습니다.
Python 3.9 이상 표준 라이브러리만 사용합니다.

```sh
# 저장소 루트에서 실행
python3 -m simulator --count 100 --duplicate-rate 0.2 --conflict-rate 0.1 --late-rate 0.1 --invalid-rate 0.05
python3 -m simulator --count 5 --dry-run       # 전송하지 않고 계획과 예시 payload만 확인
python3 -m simulator --count 1000 --rate 50    # 초당 50건으로 제한
```

| 옵션 | 전송 내용 | 기대 응답 |
|---|---|---|
| `--count` | 고유 진단 결과 | `202` |
| `--duplicate-rate` | 보낸 결과를 같은 내용으로 1~2회 다시 보냄 (S1) | `200` |
| `--conflict-rate` | 같은 `deviceId`·`eventId`로 DTC만 바꿔 보냄 (S2) | `409` |
| `--late-rate` | 이미 보낸 차량·ECU의 1~6시간 전 결과를 나중에 보냄 (S3) | `202` |
| `--invalid-rate` | `BRAND_A`인데 `swVersion`이 없는 결과 | `422` |

전송은 세 단계로 나눠 진행합니다. 고유 결과와 정책 위반을 먼저 보내고, 이어서 재전송과 충돌, 마지막으로 늦은 과거 결과를 보냅니다.
한 단계 안에서는 순서를 섞어 `--concurrency` 수만큼 동시에 보내고, 앞 단계가 모두 끝나야 다음 단계로 넘어갑니다.
그래서 기대 응답 코드가 실행할 때마다 같습니다.
기대와 다른 응답이 1건이라도 있으면 종료 코드 1을 반환합니다.
결과 요약의 `expectedResults`는 gateway에 새로 저장돼야 하는 건수입니다. 대조할 때 `python3 -m scripts.reconcile --expect-results N`에 넘기면 됩니다.

gateway 주소는 `--gateway-url`이나 환경변수 `SIM_GATEWAY_URL`로 바꿉니다. 기본값은 `http://localhost:8080`입니다.
