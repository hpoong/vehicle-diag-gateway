# scripts: 프로세스 제어·장애 주입·대조

로컬에서 실제 gateway·receiver 프로세스를 띄워 장애를 만들고, 두 DB를 대조해 누락과 중복을 판정합니다.
통합 테스트는 같은 규칙을 한 JVM 안에서 흉내 냅니다. 이 스크립트는 gateway 프로세스를 실제로 `SIGKILL`·`SIGSTOP` 하고, 두 번째 gateway 프로세스도 띄웁니다.

## 준비

```sh
python3 -m venv .venv && .venv/bin/pip install -r scripts/requirements.txt   # psycopg (대조·초기화에만 필요)
export JAVA_HOME=...                                                          # JDK 17
.venv/bin/python -m scripts.services up       # docker compose up -d --wait postgres
.venv/bin/python -m scripts.services build    # gateway·receiver bootJar
.venv/bin/python -m scripts.services start    # receiver(chaos 프로필) + gateway(gw-1)
```

기존 PostgreSQL 볼륨에 receiver DB가 없으면 [실행 가이드](../docs/getting-started.md)의 init 스크립트를 한 번 실행합니다.

## 명령

| 명령 | 설명 |
|---|---|
| `python -m scripts.services status` | postgres, gateway(:8080, gw-1), gateway2(:8082, gw-2), receiver(:8081) 상태 |
| `python -m scripts.services start\|stop\|kill\|pause\|resume <이름>` | 시작 / 그레이스풀 종료 / `SIGKILL` / `SIGSTOP` / `SIGCONT` |
| `python -m scripts.services start receiver --naive` | 중복 검사 없는 비교 모드로 receiver 시작 (S12) |
| `python -m scripts.fault NONE\|DOWN\|DROP_AFTER_COMMIT [--hold-ms N]` | receiver 장애 주입 |
| `python -m scripts.reconcile [--mode naive] [--expect-results N]` | I1~I6 판정, `SCENARIO_RESULT` 줄 출력 |
| `python -m scripts.dbreset --yes` | 로컬 두 DB의 업무 데이터 초기화 (스키마 유지) |
| `python -m scripts.scenarios list` / `run S7` / `run all` | 시나리오 실행 (시작할 때 DB를 비움) |

PID와 로그는 `build/run/`에 남습니다.
gateway는 시나리오를 빨리 확인하도록 기본보다 짧은 전송 설정으로 실행합니다. 백오프는 1~4s, 최대 시도는 6회, lease는 10s입니다.
설정을 바꾸려면 `SIM_GATEWAY_ARGS`를 지정합니다. 주소와 DB 접속 정보는 `SIM_GATEWAY_URL`, `SIM_RECEIVER_URL`, `POSTGRES_PORT`, `SIM_GATEWAY_DSN`, `SIM_RECEIVER_DSN`으로 바꿉니다.

## 시나리오

| # | 실제로 만드는 상황 | 판정 |
|---|---|---|
| S1 | 결과 30%를 같은 내용으로 재전송 | 재전송 `200`, 저장 건수 = 고유 결과 수 |
| S2 | 결과 30%를 같은 키·다른 DTC로 전송 | `409`, 기존 결과 유지 |
| S3 | 과거 시각 결과를 나중에 전송 | I6 최신 상태가 과거로 돌아가지 않음 |
| S4 | receiver `DOWN` 중 20건 전송 후 복구 | 자동 재시도로 전 건 SENT, DEAD 0 |
| S5 | `DROP_AFTER_COMMIT` 6s (readTimeout 5s 초과) | UNCERTAIN 후 재전송, DUP1 0 |
| S7 | 응답 대기 중인 gateway를 `SIGKILL` 후 재기동 | lease 만료 → ABANDONED·재선점, DUP1 0 |
| S8 | gateway 프로세스 2개로 200건 처리 | 같은 건 이중 SUCCESS 0, 두 인스턴스가 나눠 처리 |
| S9 | 응답 대기 중인 gw-1을 `SIGSTOP` → gw-2가 이어받음 → `SIGCONT` | 늦은 결과는 `late_outcome`으로만 기록, DUP1 0 |
| S10 | `DOWN` 유지 → 6회 후 DEAD → 운영 API 재처리 | 재처리 후 전 건 SENT |
| S12 | naive receiver + 응답 유실 (대조군) | 품질 건 중복 발생 (DUP1 > 0) |

S6(`SLOW`)과 S11(outbox 없는 비교)은 receiver·gateway에 해당 모드가 없어 제외했습니다.
S9는 `SIGSTOP`이 필요하므로 macOS·Linux에서만 실행할 수 있습니다.
같은 기능을 브라우저에서 쓰려면 [front](../front/README.md)를 실행합니다.
