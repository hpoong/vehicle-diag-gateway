# 결함 추적 콘솔 (생산 이력 연계 · LOT 순·역추적)

로컬 콘솔(`front/`)에서 생산 이력 연계와 LOT 순·역추적을 버튼으로 재현하고, 추적 결과가 시뮬레이터 정답과 같은지 화면에서 바로 확인한다.
조치 대상(캠페인) 전달과 S13은 이 문서 범위가 아니며 후속 문서에서 다룬다.

## 1. 현행 점검

| 영역 | 현재 상태 | 근거 |
|---|---|---|
| gateway API | 생산 이력 배치 반영, 역추적, 순추적 구현 완료 | `POST /api/v1/production/vehicle-parts`, `GET /api/v1/trace/lots`, `GET /api/v1/trace/lots/{lotNo}/vehicles` |
| simulator | `fleet` 명령으로 차량군·LOT·장착 이력을 생성하고, 생산 이력 → 진단 결과 순으로 전송하며, 정답(`expected`)을 계산 | [simulator/fleet.py](../../../../simulator/fleet.py) |
| scripts | `T1 추적 정확도` 시나리오가 fleet 전송 → 역추적 → 정답 비교 → 재전송 후 불변을 판정 | [scripts/scenarios.py](../../../../scripts/scenarios.py) |
| front 서버 | 작업 종류가 `scenario`, `simulate`, `service`뿐이다. gateway 중계는 `/ops/*`만 있다 | [front/server.py](../../../../front/server.py) |
| front 화면 | 시나리오 카드가 `scenarios.catalog()`에서 만들어지므로 **T1 카드는 이미 보인다**. 결과는 판정 목록과 로그만 나오고, `result.trace`(LOT별 API·정답 비교)는 그리지 않는다 | [front/static/app.js](../../../../front/static/app.js) |

**결론: front에는 생산 이력·추적 전용 시뮬레이션이 없다.** T1을 실행해 PASS/FAIL은 볼 수 있지만, 다음 기능은 없다.

- 차량군 파라미터(LOT 수, 결함 LOT, 결함률, 재전송률 등)를 정해 직접 전송하는 기능
- 역추적 LOT 표, 미귀속(unattributed) 3칸, 순추적 차량 목록을 보는 기능
- API 결과와 시뮬레이터 정답을 LOT 단위로 나란히 보는 기능

## 2. 목표 화면 흐름

새 탭 **결함 추적**을 탭 목록의 첫 번째에 둔다. 기존 탭은 그대로 유지한다.

```text
[1 차량군 생성] ──fleet 작업──▶ 생산 이력 batch 반영 → 진단 결과 전송(재전송 포함)
       │                              요약: 이력 rows/inserted/updated/unchanged, 전송 byKind, 저장 기대 건수
       ▼ (조회 기간·정답 자동 채움)
[2 역추적] ──GET /trace/lots──▶ LOT 표 + 미귀속 3칸 + 합계, 행마다 "정답 일치" 배지
       │ LOT 행 클릭
       ▼
[3 순추적] ──GET /trace/lots/{lot}/vehicles──▶ 현재 장착 차량 목록, 탈거 제외 N대, 정답 일치 배지
```

## 3. 구현 단위

| 순서 | 단위 | 내용 |
|---|---|---|
| 1 | TC-01 fleet 작업 | `POST /api/jobs {type:"fleet", params}`<br>`FleetParams`로 검증하고, 실패하면 400을 돌려준다.<br>"초기화 후 실행" 옵션(기본 켜짐)이면 `dbreset.reset()` 뒤에 `run_fleet`를 실행한다.<br>진행률은 `on_progress`, 로그는 `on_log`로 받는다. |
| 2 | TC-02 마지막 차량군 보관 | 마지막 fleet의 `runId`, `params`, `window`, `expected`, 전송 요약을 서버 메모리에 보관한다. `GET /api/fleet/last`로 조회한다. 서버를 재시작하면 사라진다. |
| 3 | TC-03 추적 중계 | `GET /api/trace/lots?…`, `GET /api/trace/lots/{lot}/vehicles?…`를 gateway로 중계한다. 기존 `_ops` 중계를 접두사만 다른 공용 함수로 바꾼다. gateway 응답 상태와 본문은 그대로 돌려주고, 연결에 실패하면 502를 돌려준다. |
| 4 | TC-04 차량군 생성 화면 | 입력: 차량 수, LOT 수, 결함 LOT, 결함률, 기본 고장률, LOT 미확인률, 교체율, 이력 없음 비율, 차량당 진단 수, 재전송률, seed, 초기화 여부.<br>결과: 생산 이력·진단 전송 요약과 실행 로그. |
| 5 | TC-05 역추적 화면 | 입력: ECU, DTC, from, to. 마지막 fleet의 기간으로 자동 채운다.<br>LOT 표 열: LOT, 고장 차량, 진단 차량, 장착 차량, 고장률, 다른 LOT 고장률, 표본 부족.<br>표 아래에 미귀속 3칸(LOT 미확인·이력 없음·이력 모호), 합계, `notes`를 보여준다.<br>조회 조건이 마지막 fleet과 같으면 LOT별로 정답 일치/불일치 배지를 단다. |
| 6 | TC-06 순추적 화면 | LOT 행을 클릭하면 현재 장착 차량 목록을 보여준다(VIN, 시리얼, 장착 시각, 최근 진단 시각, 최근 DTC).<br>`currentVehicles`, `excludedRemoved`, `truncated`를 표시하고, `expected.forward[lot]`와 비교한다. |
| 7 | TC-07 T1 결과 표 | 시나리오 결과에 `trace`가 있으면 LOT별 API·정답 비교 표를 `#jobResult`에 그린다. |
| 8 | TC-08 문서 | [front/README.md](../../../../front/README.md) 기능 표에 결함 추적 탭을 추가한다. |

스타일은 기존 `style.css`의 `card`, `grid`, `tag`, `checks` 컴포넌트를 재사용하고, 새 스타일은 최소화한다.

## 4. 정답 비교 규칙

- 비교 필드
  - LOT별: `faultVehicles`, `diagnosedVehicles`, `installedVehicles`
  - 미귀속 3칸과 `totals`
  - 순추적: `currentVehicles`, `excludedRemoved`
- 정답 일치 배지는 **ECU=TCM, DTC=P0700이고, 기간이 마지막 fleet의 `window`와 같을 때만** 표시한다. 조건을 바꾸면 "정답 없음"으로 표시한다.
- 역추적은 `diagnostic_result`·`ecu_observation`·`vehicle_part`만 읽는다. 그래서 receiver 전송(outbox 소진)을 기다리지 않고 바로 조회할 수 있다.
- 기간 일치는 **문자열이 아니라 시각 값으로** 비교한다. fleet `window`는 초 단위(`…T10:30:37Z`)이고, API 응답의 `from`·`to`는 마이크로초까지 나올 수 있다.

## 5. 현행 흐름 대비 검증 항목

구현한 뒤 아래 순서로 확인하고, 결과를 이 문서에 기록한다.

| # | 확인 내용 | 기대 결과 |
|---|---|---|
| V1 | 초기화 후 fleet(300대, 6 LOT, 결함 L-2403, 재전송 0.3, seed 42) → 역추적 | 모든 LOT와 미귀속·합계가 정답과 일치하고, 결함 LOT가 고장 차량 1위 |
| V2 | V1 직후 LOT 클릭 → 순추적 | `currentVehicles`, `excludedRemoved`가 `expected.forward`와 일치 |
| V3 | 초기화 없이 fleet를 두 번 실행 | **불일치가 나는 것이 정상이다.** LOT 이름(`L-2401`…)이 매번 같고 두 실행의 조회 기간이 겹친다. 그래서 장착·고장·진단 차량 수와 순추적 수가 모두 누적된다. 화면에 "초기화 없이 실행해 정답 비교가 맞지 않을 수 있음" 경고를 표시한다 |
| V4 | 시나리오 탭에서 T1 실행 | PASS, TC-07 표에 LOT별 API·정답이 표시됨 |
| V5 | gateway 중지 상태에서 역추적 | 502와 "gateway 연결 실패" 토스트 |
| V6 | 잘못된 기간(from ≥ to, 90일 초과) | gateway 400 `invalid-trace-window` 메시지가 그대로 표시됨 |
| V7 | 회귀: 시뮬레이터 전송, S1 실행, 운영 콘솔, 대조 | 기존 동작이 바뀌지 않음 |

## 6. 전제 검증 결과 (2026-11-08)

front를 구현하기 전에, 플랜이 기대는 현재 코드 흐름을 CLI와 gateway API로 직접 확인했다.
환경: 로컬 postgres, receiver, gateway gw-1을 실행한 상태. 테스트는 `scripts.services`로 띄운 실제 프로세스로 했다.

| 전제 | 확인 방법 | 결과 |
|---|---|---|
| fleet 실행 직후(outbox 소진 전) 역추적이 정답과 같다 | 초기화 → `run_fleet`(V1 조건) → 바로 역추적 | **일치.** LOT 6/6, 미귀속 3/3, 합계 고장 39 / 진단 300, 결함 LOT `L-2403` 1위(고장 28 / 진단 49, 고장률 0.5714, 다른 LOT 0.0424) |
| 순추적이 `expected.forward`와 같다 | 위 직후 6개 LOT 순추적 | **일치.** 6/6 |
| fleet 전송 규모 | 위 실행 요약 | 생산 이력 312행·1 batch(inserted 312), 진단 original 600건 `202`, 재전송 250건 `200`, 0.6초 |
| 초기화 없이 재실행하면 비교가 깨진다 | seed만 바꿔 한 번 더 실행 | **불일치.** 장착·고장·진단 차량과 순추적 수가 모두 누적됨 → V3 문구를 고침 |
| 기간 오류는 gateway가 400으로 돌려준다 | from ≥ to, 90일 초과 | `400 invalid-trace-window`, `errors[].rule` = `TRACE.WINDOW_ORDER` / `TRACE.WINDOW_TOO_LONG` |
| 기본 기간 | from·to 없이 조회 | 최근 30일 (`gateway.trace.default-window`) |
| gateway가 중지되면 중계가 502를 낼 수 있다 | gateway 중지 후 `scripts.common.http` 호출 | `status=0`, `{"error": "... Connection refused"}` → 기존 `_ops`의 502 변환을 그대로 쓰면 된다 |
| T1 시나리오가 통과하고 `trace` 결과를 돌려준다 | `scripts.scenarios run T1` | **PASS (79.3초).** LOT 6/6, 미귀속 3/3, 재전송 후 600→600 불변, I1~I6·E1 PASS |
| 기존 시나리오 회귀 없음 | `scripts.scenarios run S1` | **PASS (5.5초)** |

추가로 확인한 점:

- **응답 형식:** 역추적 응답 키는 `ecuId`, `dtcCode`, `from`, `to`, `minSample`(20), `totals`, `lots`, `unattributed`, `evidence`, `notes`이다. `notes`에는 고장률 정의, LOT 귀속 기준, "원인 후보" 문구 3줄이 들어 있어 화면 하단에 그대로 표시하면 된다.
- **fleet 작업 시간:** T1은 outbox 소진(600건)을 기다리느라 약 79초가 걸렸다. fleet 작업은 소진을 기다리지 않고 끝내고, 실시간 현황 타일에서 전송이 이어지는 것을 보여 주면 된다.
- **진행률·로그 연결:** `run_fleet`는 `on_log`, `on_progress`, `stop_event`를 받고, `on_progress`가 넘기는 객체에는 `done`·`total`이 있다. 기존 `job_simulate`와 같은 방식으로 Job에 연결할 수 있다.

**결론: 플랜의 전제는 현재 코드와 맞는다.** 바꿀 점은 V3 문구(누적 범위)와 기간 비교 방식 두 가지이며, 위 본문에 반영했다.

## 7. 구현 결과 (2026-11-08)

| 단위 | 구현 위치 |
|---|---|
| TC-01·02 | [front/server.py](../../../../front/server.py) `job_fleet`, `fleet_params`, `LAST_FLEET`, `GET /api/fleet/last`.<br>DB 초기화, 시나리오·시뮬레이터 시작 시 `mark_fleet_stale`로 정답 비교를 끈다. |
| TC-03 | `_ops`를 `_relay(prefix, …)`로 일반화. `/api/trace/*`는 GET만 허용한다(그 외 400). |
| TC-04~06 | [index.html](../../../../front/static/index.html) `#tab-trace`, [app.js](../../../../front/static/app.js) "결함 추적" 절, [style.css](../../../../front/static/style.css) `/* trace */` 절 |
| TC-07 | `t1TraceHtml`: 시나리오 결과의 `trace`를 LOT별 API·정답 표로 표시 |
| TC-08 | [front/README.md](../../../../front/README.md) 기능 표·정답 비교 조건 |

함께 고친 기존 결함: `GET /api/logs/{name}`이 `parts[:2]` 키 비교 때문에 항상 404였다. 그래서 "서비스 로그" 탭이 동작하지 않았다.

### 검증 결과

실행 중인 postgres·receiver·gateway gw-1에 front 서버를 띄우고, 브라우저와 curl로 확인했다.

| # | 결과 |
|---|---|
| V1 | 화면 차량군 전송 → 자동 역추적. 배지 "정답 일치 · LOT 6/6 · 미귀속 ✔ · 합계 ✔". `L-2403`이 고장 28 / 진단 49 / 장착 51(57.1%)로 1위 |
| V2 | `L-2403` 클릭 → 순추적 현재 장착 48 / 탈거 제외 3, "정답 일치" |
| V3 | 초기화를 끄고 seed 7로 재전송 → 초기화 경고와 "정답 불일치 · LOT 0/6", 순추적도 "정답 불일치"(98, 정답 50) |
| V4 | 시나리오 탭 T1 → 완료 79.3초, 모든 판정 ✔, LOT별 API·정답 표 6행 모두 ✔ |
| V5 | (curl만) 응답하지 않는 gateway 주소로 중계 → `502 gateway 연결 실패`. 화면에서 gateway를 중지하고 조회하는 경로는 미확인 |
| V6 | (curl만) from ≥ to → `400`, `errors[0].message` = "from은 to보다 앞이어야 합니다". 화면 표시는 미확인 |
| V7 | `/api/ops/summary`·`/api/ops/events` 200, 실시간 현황 정상, DB 초기화 후 배지 "정답 없음 · DB 초기화 후 데이터가 없습니다", 서비스 로그 200(위 결함 수정 후) |
| 입력 검증 | `vehicles=0`, LOT 범위 밖 결함 LOT → 400과 메시지 |

### 추가 화면 검증과 수정 (2026-11-08)

1차 검증에서 화면으로 확인하지 않은 항목을 브라우저에서 실제로 실행했다. 발견한 결함은 고친 뒤 다시 확인했다.

| 항목 | 결과 |
|---|---|
| 잘못된 기간(화면) | from ≥ to → "from은 to보다 앞이어야 합니다", 90일 초과 → "조회 기간은 최대 90일입니다" |
| 형식 오류(화면) | **수정:** 필드 이름 없이 영어 메시지만 나왔다 → `from: must be an ISO offset date-time`처럼 필드를 붙여 표시 |
| 소문자 ECU·DTC | **수정:** `p0700`을 넣으면 정규식 오류가 났다 → 조회 전에 공백 제거·대문자로 바꿔 정상 조회, "정답 일치" |
| 조회 실패 시 순추적 | **수정:** 조회가 실패해도 이전 순추적 카드가 남았다 → 실패하면 숨김 |
| 조건·기간 변경 | DTC P0420 / ECU ECM → "정답 없음 · 조건 다름", 기간을 비우면(기본 30일) → "정답 없음 · 기간 다름", "차량군 기간" 버튼 → 다시 "정답 일치" |
| gateway 중지(화면) | 역추적 → "gateway 연결 실패: Connection refused" |
| 생산 이력 실패 | **수정:** 이 경우 DB는 이미 초기화됐는데 이전 차량군 정답이 비교 기준으로 남았고, 요약에 정답 수치도 보였다 → 전송을 시작할 때 이전 정답을 무효로 하고, 실패하면 정답 칸을 "-"로, 배지를 "정답 없음 · 전송이 끝까지 성공하지 않아…"로 표시 |
| 전송 중단 | **수정:** 결함 추적 탭에 중단 버튼이 없었다 → 버튼 추가. 5,000대 전송 중 중단 → 상태 "중단", 정답 비교 꺼짐 |
| 큰 차량군 | 5,000대: 생산 이력 5,207행·6 batch, 진단 original 10,000 + 재전송 4,572, 9.7초 → "정답 일치 · LOT 6/6", 순추적 정답 일치(상위 200대만 표시) |
| 시뮬레이터 실행 후 | "정답 없음 · 시뮬레이터 100건 실행 후 DB가 바뀌었습니다" |
| front 서버 재시작 후 | `/api/fleet/last` = null, 차량군을 다시 보내면 정상 |
| 화면의 gateway 시작 버튼 | front 서버를 `JAVA_HOME`과 함께 실행하면 정상 기동 |
| 라이트 모드·390px 폭 | **수정:** 표 셀이 줄바꿈되어 `L-` / `2403`으로 갈라졌다 → 셀 줄바꿈 금지. 문서 가로 넘침 없음, 표만 카드 안에서 가로 스크롤 |
| 브라우저 콘솔 | 오류·경고 없음 |
| 시나리오 전체 회귀 | `scripts.scenarios run all` → S1·S2·S3·S4·S5·S7·S8·S9·S10·S12·T1 모두 PASS (T1 82.1초, S12는 비교 모드라 dup1=7이 기대값) |

## 8. 결정이 필요한 점

- **fleet 실행 전 초기화를 기본으로 켤지:** 정답 비교가 의미 있으려면 초기화가 필요하므로 기본값은 켜짐으로 둔다. 기존 시뮬레이터 데이터를 남기고 싶을 때만 끈다.
- **재전송 불변 확인 버튼:** T1이 이미 판정하므로 1차 범위에서는 뺀다. 화면에서도 확인하고 싶다면 마지막 fleet의 `plan`을 메모리에 보관하고, 재전송 뒤 같은 조건으로 다시 조회하는 버튼을 추가한다.
