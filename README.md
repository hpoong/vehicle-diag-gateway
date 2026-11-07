# Vehicle Diagnostic Gateway

차량 진단 결과 연계 gateway의 Java 17 프로젝트입니다.
[문서 가이드](docs/README.md), [아키텍처](docs/architecture/overview.md), [DB 설계](docs/db/README.md), [개발 컨벤션](docs/convention/conventions.md)을 기준으로 구성했습니다.

## 기술 및 구조

- Java 17, Spring Boot 3.5.16, Gradle Wrapper 8.14.3
- Spring MVC, Bean Validation, MyBatis 3.0.5, Flyway, PostgreSQL 16, Actuator
- `gateway/`: 독립 실행 Spring Boot 애플리케이션
- `web-support/`: 두 서비스가 재사용하는 일반 JAR (응답, 예외, 요청 ID 추적)
- `ingest`, `policy`, `delivery`, `ops`, `common`: 설계 책임별 패키지
- `gateway/src/main/resources/db/migration/`: 수신·outbox·운영 감사 테이블

진단 결과 수신·정규화·중복 판정·공통/브랜드 검증·원자적 저장·최신 상태 갱신·outbox 예약·결과 조회를 구현했습니다.
[수신 기능 구현 현황](docs/plan/gateway/README.md)에서 단계별 동작과 검증 기록을 확인할 수 있습니다.
[일반 설명용](docs/flow/gateway-overview.html)과 [개발자용](docs/flow/gateway-developer.html) 슬라이드 HTML을 제공합니다.
outbox 전송 워커와 운영 API는 후속 구현 대상입니다.
`receiver/`와 시나리오 테스트(JUnit + Testcontainers, 정합성 대조 포함)는 후속 단계에서 구성합니다.

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

## 빌드와 검증

저장소 루트에서 실행합니다.

**Windows (PowerShell)**

```powershell
.\gradlew.bat clean build
# DB 없이 공통 HTTP 응답/예외 처리 검증
.\gradlew.bat :web-support:integrationTest
# Docker 필요: 실제 PostgreSQL 16으로 Flyway V1~V3와 health 확인
.\gradlew.bat :gateway:integrationTest
java -jar gateway\build\libs\gateway-0.0.1-SNAPSHOT.jar
```

**macOS (zsh)**

```sh
./gradlew clean build
# DB 없이 공통 HTTP 응답/예외 처리 검증
./gradlew :web-support:integrationTest
# Docker 필요: 실제 PostgreSQL 16으로 Flyway V1~V3와 health 확인
./gradlew :gateway:integrationTest
java -jar gateway/build/libs/gateway-0.0.1-SNAPSHOT.jar
```

단위 테스트는 `test`, PostgreSQL 기반 수신·조회 및 기동 검증은 별도 `integrationTest` 태스크입니다.
CI에서는 `build integrationTest`를 함께 실행합니다. H2는 사용하지 않습니다.
공통 응답 사용법과 receiver 연결 방법은 [공통 웹 기반](docs/architecture/web-support.md)을 참고하세요.

로컬 DB 정지(데이터 유지): 저장소 루트에서 `docker compose down`.
단말 인증은 설계 범위 밖이며, 실제 서비스에서는 인증 주체와 `deviceId`를 대조해야 합니다.
