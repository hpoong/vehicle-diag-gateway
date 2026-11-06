# Vehicle Diagnostic Gateway

차량 진단 결과 연계 gateway의 Java 17 초기 프로젝트입니다.
[문서 가이드](docs/README.md), [아키텍처](docs/architecture/overview.md), [DB 설계](docs/db/README.md), [개발 컨벤션](docs/convention/conventions.md)을 기준으로 구성했습니다.

## 기술 및 구조

- Java 17, Spring Boot 3.5.16, Gradle Wrapper 8.14.3
- Spring MVC, Bean Validation, MyBatis 3.0.5, Flyway, PostgreSQL 16, Actuator
- `gateway/`: 독립 실행 Spring Boot 애플리케이션
- `web-support/`: 두 서비스가 재사용하는 일반 JAR (응답, 예외, 요청 ID 추적)
- `ingest`, `policy`, `delivery`, `ops`, `common`: 설계 책임별 패키지
- `gateway/src/main/resources/db/migration/`: 수신·outbox·운영 감사 테이블

현재는 프로젝트 골격, 설정, 스키마와 기동 검증까지 구성되어 있습니다. 진단 수신 API, 정책,
outbox 워커, 운영 API는 후속 구현 대상입니다. 공통 HTTP 응답·예외 처리는 구성되어 있습니다.
`receiver/`와 시나리오 테스트(JUnit + Testcontainers, 정합성 대조 포함)는 후속 단계에서 구성합니다.

## 로컬 실행 (PowerShell)

JDK 17과 Docker Desktop의 Linux 컨테이너 엔진이 필요합니다. Gradle은 Wrapper가 내려받습니다.
첫 실행에는 의존성과 컨테이너 이미지 다운로드를 위한 네트워크 연결이 필요합니다.

```powershell
# 설치 경로에 맞게 지정
$env:JAVA_HOME = 'D:\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

# 저장소 루트에서 PostgreSQL 실행
docker compose up -d --wait postgres

Set-Location gateway
.\gradlew.bat bootRun
```

기동 시 Flyway가 gateway DB에 V1~V3 스키마를 적용합니다.
상태 확인: `http://localhost:8080/actuator/health`

기본 DB는 `localhost:5432/gateway`, 계정은 `gateway`, 로컬 비밀번호는 `gateway-local`입니다.
환경변수 `GATEWAY_DB_URL`, `GATEWAY_DB_USERNAME`, `GATEWAY_DB_PASSWORD`, `GATEWAY_PORT`로 변경할 수 있습니다.
Compose의 `POSTGRES_PORT`를 변경하면 `GATEWAY_DB_URL`도 같은 포트로 맞춥니다.
Compose의 비밀번호 설정은 빈 데이터 볼륨을 최초 초기화할 때 적용됩니다.

## 빌드와 검증

`gateway/`에서 실행합니다.

```powershell
.\gradlew.bat clean build
# DB 없이 공통 HTTP 응답/예외 처리 검증
.\gradlew.bat :web-support:integrationTest
# Docker 필요: 실제 PostgreSQL 16으로 Flyway V1~V3와 health 확인
.\gradlew.bat integrationTest
java -jar build/libs/gateway-0.0.1-SNAPSHOT.jar
```

현재 단위 테스트는 없으며, DB 기동 검증은 별도 `integrationTest` 태스크입니다.
CI에서는 `build integrationTest`를 함께 실행합니다. H2는 사용하지 않습니다.
Linux/macOS에서는 `sh ./gradlew`로 같은 태스크를 실행할 수 있습니다.

저장소 루트에서는 `.\gateway\gradlew.bat -p . :gateway:build :web-support:integrationTest`로 빌드합니다.
공통 응답 사용법과 receiver 연결 방법은 [공통 웹 기반](docs/web-support.md)을 참고하세요.

로컬 DB 정지(데이터 유지): 저장소 루트에서 `docker compose down`.
단말 인증은 설계 범위 밖이며, 실제 서비스에서는 인증 주체와 `deviceId`를 대조해야 합니다.
