# front: Diag Gateway Lab

[simulator](../simulator/README.md)와 [scripts](../scripts/README.md)를 브라우저에서 다루는 로컬 콘솔입니다.
Python 표준 라이브러리 HTTP 서버로 실행하며, 프런트엔드 빌드 도구는 사용하지 않습니다.

```sh
.venv/bin/python front/server.py      # http://127.0.0.1:8070 (SIM_FRONT_PORT로 변경)
```

준비 과정(venv, JDK, PostgreSQL, bootJar)은 [scripts/README.md](../scripts/README.md)와 같습니다.
PostgreSQL 실행과 bootJar 빌드는 화면에서도 할 수 있습니다.

| 영역 | 기능 |
|---|---|
| 서비스 | postgres·receiver·gateway gw-1·gw-2 상태, 시작·정지·강제 종료(`SIGKILL`)·일시 정지(`SIGSTOP`)·재개 |
| 장애 주입 | receiver `NONE` / `DOWN` / `DROP_AFTER_COMMIT`(holdMs) |
| 시뮬레이터 | 건수, 재전송·충돌·지연·정책 위반 비율, 동시 전송 수, 속도 제한 |
| 실시간 연계 현황 | outbox PENDING·SENT·DEAD와 receiver 반영 건수 추이, 시도 결과(outcome)별 건수 |
| 시나리오 | S1~S12를 한 번에 하나씩 또는 전체 실행, 실행 로그와 판정 |
| 운영 콘솔 | gateway `/ops/summary`, 상태별 목록, 시도 이력, DEAD 수동 재처리 |
| 대조 결과 | I1~I6 판정과 `SCENARIO_RESULT` 줄 |
| 서비스 로그 | `build/run/*.log` 꼬리 |

서버는 `127.0.0.1`에만 바인딩하며 인증이 없습니다. 로컬 개발 전용입니다.
DB 초기화, 시나리오, 프로세스 제어는 실제 로컬 DB와 프로세스를 바꿉니다.
