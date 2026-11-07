# 개발 환경 및 실행 가이드

차량 진단 결과 연계 gateway의 기술 구성, 로컬 실행, 빌드·검증 방법을 정리합니다.
프로젝트 소개와 핵심 설계는 [프로젝트 README](../README.md)를 참고하세요.
[문서 가이드](README.md), [아키텍처](architecture/overview.md), [DB 설계](db/README.md), [개발 컨벤션](convention/conventions.md)을 기준으로 구성했습니다.

## 기술 및 구조

- Java 17, Spring Boot 3.5.16, Gradle Wrapper 8.14.3
- Spring MVC, Bean Validation, MyBatis 3.0.5, Flyway, PostgreSQL 16, Actuator
- `gateway/`: 독립 실행 Spring Boot 애플리케이션
- `receiver/`: 품질 이벤트 멱등 수신·장애 주입용 독립 Spring Boot 애플리케이션
- `web-support/`: 두 서비스가 재사용하는 일반 JAR (응답, 예외, 요청 ID 추적)
- `ingest`, `policy`, `delivery`, `ops`, `common`: 설계 책임별 패키지
- `gateway/src/main/resources/db/migration/`: 수신·outbox·운영 감사 테이블

진단 결과 수신·정규화·중복 판정·공통/브랜드 검증·원자적 저장·최신 상태 갱신·outbox 예약·결과 조회를 구현했습니다.
[수신 기능 구현 현황](plan/gateway/01-ingest-design/README.md)에서 단계별 동작과 검증 기록을 확인할 수 있습니다.
[일반 설명용](flow/gateway-overview.html)과 [개발자용](flow/gateway-developer.html) 슬라이드 HTML을 제공합니다.
Receiver도 [일반 설명용](flow/receiver-overview.html)과 [개발자용](flow/receiver-developer.html) 슬라이드 HTML을 제공합니다.
outbox 전송 워커와 운영 API를 구현했습니다.
[전송·재시도 구현 현황](plan/gateway/02-delivery-retry-design/README.md), [운영 API·장애 시나리오](plan/gateway/04-ops-verify-design/README.md), [시나리오 검증 결과](results.md)에서 상세 내용을 확인할 수 있습니다.
receiver는 품질 이벤트 검증·중복 제거와 장애 주입, naive 비교 모드를 제공합니다.
[receiver 구현 현황](plan/receiver/03-receiver-design/README.md)에서 API와 검증 범위를 확인할 수 있습니다.

## 로컬 실행

JDK 17과 Docker Desktop의 Linux 컨테이너 엔진이 필요합니다. Gradle은 Wrapper가 내려받습니다.
첫 실행에는 의존성과 컨테이너 이미지 다운로드를 위한 네트워크 연결이 필요합니다.
모든 명령은 저장소 루트에서 실행합니다. Gradle Wrapper는 루트에 하나만 있습니다.

**Windows (PowerShell)**

```powershell
# 설치 경로에 맞게 지정
$env:JAVA_HOME = 'D:\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

docker compose up -d --wait postgres
.\gradlew.bat :gateway:bootRun
```

**macOS (zsh)**

```sh
# 설치 경로에 맞게 지정 (~/.zshrc에 넣어 두면 매번 지정하지 않아도 됩니다)
export JAVA_HOME="$HOME/java/jdk-17.0.2.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

docker compose up -d --wait postgres
./gradlew :gateway:bootRun
```

기동 시 Flyway가 gateway DB에 V1~V3 스키마를 적용합니다.
상태 확인: `http://localhost:8080/actuator/health`

기본 DB는 `localhost:5432/gateway`, 계정은 `gateway`, 로컬 비밀번호는 `gateway-local`입니다.
환경변수 `GATEWAY_DB_URL`, `GATEWAY_DB_USERNAME`, `GATEWAY_DB_PASSWORD`, `GATEWAY_PORT`로 변경할 수 있습니다.
Compose의 `POSTGRES_PORT`를 변경하면 `GATEWAY_DB_URL`도 같은 포트로 맞춥니다.
Compose의 비밀번호 설정은 빈 데이터 볼륨을 최초 초기화할 때 적용됩니다.

receiver는 별도 `receiver` DB를 사용하며, 별도 터미널에서 실행합니다.
빈 PostgreSQL 볼륨의 최초 초기화 시 Compose init 스크립트가 receiver 계정과 DB를 생성합니다.

```powershell
# Windows (PowerShell)
.\gradlew.bat :receiver:bootRun
```

```sh
# macOS (zsh)
./gradlew :receiver:bootRun
```

상태 확인: `http://localhost:8081/actuator/health`
기본 DB는 `localhost:5432/receiver`, 계정은 `receiver`, 로컬 비밀번호는 `receiver-local`입니다.
환경변수 `RECEIVER_DB_URL`, `RECEIVER_DB_USERNAME`, `RECEIVER_DB_PASSWORD`, `RECEIVER_PORT`로 변경할 수 있습니다.
`POSTGRES_PORT` 변경 시 `RECEIVER_DB_URL`도 같은 포트로 맞춥니다.
기동 시 Flyway V1이 수신 이벤트·품질 건·naive 비교 테이블을 생성합니다.

기존 볼륨에서는 init 스크립트가 다시 실행되지 않습니다. 기존 데이터를 유지하면서 receiver DB만 추가하려면
다음 명령을 **한 번만** 실행합니다. Compose에 설정한 `RECEIVER_DB_PASSWORD`를 사용합니다.

```sh
docker compose exec postgres bash /docker-entrypoint-initdb.d/01-create-receiver-db.sh
```

장애 주입 API는 `chaos` 프로필에서만 열립니다.

```sh
./gradlew :receiver:bootRun --args='--spring.profiles.active=chaos'
# 중복 제거 없는 비교 실험: --receiver.compare.naive-insert=true 추가
```

품질 이벤트는 `POST /api/v1/quality-events`, 장애 설정은 `POST /admin/faults`입니다.
API·해시·트랜잭션·장애 동작은 [receiver 구현 현황](plan/receiver/03-receiver-design/README.md)을 참고하세요.

## 시뮬레이터·장애 콘솔 (선택)

실제 gateway·receiver 프로세스에 가상 단말 데이터를 보내고, 프로세스 강제 종료 같은 장애를 만든 뒤 두 DB를 대조합니다.
Python 3.9 이상이 필요합니다. DB 대조와 초기화에는 psycopg를 사용합니다.

```sh
python3 -m venv .venv && .venv/bin/pip install -r scripts/requirements.txt
.venv/bin/python -m scripts.services up        # PostgreSQL
.venv/bin/python -m scripts.services build     # bootJar
.venv/bin/python front/server.py               # 브라우저 콘솔 http://127.0.0.1:8070
# 또는 CLI
.venv/bin/python -m scripts.services start
.venv/bin/python -m simulator --count 100 --duplicate-rate 0.2
.venv/bin/python -m scripts.reconcile
.venv/bin/python -m scripts.scenarios run all
```

`bootRun`으로 띄운 서비스와 포트가 겹치면 스크립트로 시작할 수 없습니다. 먼저 `bootRun`을 종료합니다.
명령과 시나리오는 [simulator](../simulator/README.md), [scripts](../scripts/README.md), [front](../front/README.md)를 참고하세요.

## 빌드와 검증

저장소 루트에서 실행합니다.

**Windows (PowerShell)**

```powershell
.\gradlew.bat clean build
# DB 없이 공통 HTTP 응답/예외 처리 검증
.\gradlew.bat :web-support:integrationTest
# Docker 필요: 실제 PostgreSQL 16으로 Flyway V1~V3와 health 확인
.\gradlew.bat :gateway:integrationTest :receiver:integrationTest
java -jar gateway\build\libs\gateway-0.0.1-SNAPSHOT.jar
```

**macOS (zsh)**

```sh
./gradlew clean build
# DB 없이 공통 HTTP 응답/예외 처리 검증
./gradlew :web-support:integrationTest
# Docker 필요: 실제 PostgreSQL 16으로 Flyway V1~V3와 health 확인
./gradlew :gateway:integrationTest :receiver:integrationTest
java -jar gateway/build/libs/gateway-0.0.1-SNAPSHOT.jar
```

단위 테스트는 `test`, PostgreSQL 기반 수신·조회 및 기동 검증은 별도 `integrationTest` 태스크입니다.
CI에서는 `build integrationTest`를 함께 실행합니다. H2는 사용하지 않습니다.
공통 응답 사용법과 receiver 연결 방법은 [공통 웹 기반](architecture/web-support.md)을 참고하세요.

로컬 DB 정지(데이터 유지): 저장소 루트에서 `docker compose down`.
단말 인증은 설계 범위 밖이며, 실제 서비스에서는 인증 주체와 `deviceId`를 대조해야 합니다.
