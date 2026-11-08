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
| 결함 추적 | 차량군 생성(생산 이력 → 진단 결과 전송), 역추적 LOT 표·미귀속·합계, LOT 클릭 시 순추적. 시뮬레이터 정답과 일치 여부를 배지로 표시 |
| 서비스 | postgres·receiver·gateway gw-1·gw-2 상태, 시작·정지·강제 종료(`SIGKILL`)·일시 정지(`SIGSTOP`)·재개 |
| 장애 주입 | receiver `NONE` / `DOWN` / `DROP_AFTER_COMMIT`(holdMs) |
| 시뮬레이터 | 건수, 재전송·충돌·지연·정책 위반 비율, 동시 전송 수, 속도 제한 |
| 실시간 연계 현황 | outbox PENDING·SENT·DEAD와 receiver 반영 건수 추이, 시도 결과(outcome)별 건수 |
| 시나리오 | S1~S12·T1을 한 번에 하나씩 또는 전체 실행, 실행 로그와 판정 (T1은 LOT별 API·정답 표) |
| 운영 콘솔 | gateway `/ops/summary`, 상태별 목록, 시도 이력, DEAD 수동 재처리 |
| 대조 결과 | I1~I6 판정과 `SCENARIO_RESULT` 줄 |
| 서비스 로그 | `build/run/*.log` 꼬리 |

정답 비교는 마지막 차량군과 ECU·DTC·기간이 같고, 그 뒤로 DB 초기화·시나리오·시뮬레이터 전송이 없었을 때만 표시합니다.
차량군은 기본으로 DB를 초기화한 뒤 전송합니다. LOT 이름이 매번 같으므로 초기화하지 않으면 이전 데이터가 누적되어 정답과 달라집니다.

서버는 `127.0.0.1`에만 바인딩하며 인증이 없습니다. 로컬 개발 전용입니다.
DB 초기화, 시나리오, 프로세스 제어는 실제 로컬 DB와 프로세스를 바꿉니다.
