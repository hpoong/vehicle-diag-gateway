# 프로젝트 문서 가이드 (Documentation Hub)

이 디렉토리는 **차량 진단 결과 연계 게이트웨이(vehicle-diag-gateway)** 프로젝트의 시스템 아키텍처, 데이터베이스 설계, 개발 표준 문서를 체계적으로 정리한 곳입니다.

---

## 📂 문서 디렉토리 구조

```text
docs/
├── README.md                      # 📌 (본 문서) 문서 전체 목차 및 네비게이션
├── getting-started.md             # 개발 환경·로컬 실행·빌드 및 검증 가이드
├── results.md                     # 장애 시나리오 실제 실행 결과
│
├── architecture/                  # 🏛️ 시스템 아키텍처 및 모듈 명세
│   ├── overview.md                # 전체 시스템 구성도, 서비스 역할, 처리 흐름
│   └── web-support.md             # 공통 웹 라이브러리 (HTTP 응답, 예외, Request-Id)
│
├── db/                            # 💾 데이터베이스 설계 및 정합성 명세
│   ├── README.md                  # DB 아키텍처 개요 및 양 시스템 간 데이터 계약
│   ├── gateway-db.md              # Gateway DB 설계 (6개 테이블, SKIP LOCKED 선점)
│   └── receiver-db.md             # Receiver DB 설계 (3개 테이블, 멱등 반영, S12 대조군)
│
├── convention/                    # 📏 개발 표준 및 컨벤션
│   └── conventions.md             # 코딩 규칙, API/에러 규격, SQL/Flyway, Git 커밋
│
└── plan/                          # 📝 세부 기능 구현 계획 및 구현 내용
    └── gateway/
        └── 01-ingest-design/      # 수신 설계 구현 (IN-01~12)
            ├── README.md                  # 전체 구현 현황 및 검증 기록
            ├── in-01-ingest-api.md        # IN-01 수신 API·요청 DTO
            ├── in-02-error-response.md    # IN-02 공통 오류 응답 (RFC 9457)
            ├── in-04-payload-hash.md      # IN-04 정규화·payload_hash
            ├── in-05-duplicate-check.md   # IN-05 중복 수신 판정
            ├── in-03-request-log.md       # IN-03 요청 추적 로그
            ├── in-06-common-rule.md       # IN-06 공통 검증
            ├── in-07-brand-policy.md      # IN-07 브랜드 정책
            ├── in-08-save-transaction.md  # IN-08 원자적 저장
            ├── in-09-latest-upsert.md     # IN-09 최신 상태 갱신
            ├── in-10-outbox-payload.md    # IN-10 전송 본문
            ├── in-11-ingest-response.md   # IN-11 수신 응답
            └── in-12-result-query.md      # IN-12 결과 조회
```

---

## 📑 주요 문서 바로가기

- [프로젝트 소개](../README.md)
- [구현 상세와 검증 요약](architecture/implementation-notes.md)
- [개발 환경 및 실행 가이드](getting-started.md): 기존 README의 기술 구성·설정·실행·검증 안내
- [README 도식 원본 HTML](assets/readme/diagrams.html): 구조·상태·응답 유실·복구·검증 비교 이미지
- 실제 프로세스 재현 도구: [가상 단말 simulator](../simulator/README.md) · [장애 주입·대조 scripts](../scripts/README.md) · [로컬 콘솔 front](../front/README.md)

### 1. 시스템 아키텍처 ([`architecture/`](architecture/))
- [**시스템 아키텍처 개요 (`architecture/overview.md`)**](architecture/overview.md)  
  - 게이트웨이(`gateway`)와 가상 품질 시스템(`receiver`)의 2개 Spring Boot 독립 서비스 구조
  - 단말 시뮬레이션 및 데이터 정합성 검증 흐름
- [**공통 웹 모듈 명세 (`architecture/web-support.md`)**](architecture/web-support.md)  
  - 두 서비스가 공유하는 `web-support` 라이브러리 규격
  - 성공 응답(`ApiResponse`), 에러 응답(RFC 9457 `ProblemDetail`), `X-Request-Id` 추적

### 2. 데이터베이스 설계 ([`db/`](db/))
- [**DB 아키텍처 및 정합성 설계 개요 (`db/README.md`)**](db/README.md)  
  - PostgreSQL 16 기반 2개 논리적 Database 분리 원칙
  - Gateway ↔ Receiver 간 논리적 계약(Contract) 및 정합성 불변식
- [**Gateway DB 설계 요약 (`db/gateway-db.md`)**](db/gateway-db.md)  
  - `diagnostic_result`, `outbox_event` 등 6개 테이블 정의서 및 ERD
  - `FOR UPDATE SKIP LOCKED` 선점 쿼리, 조건부 최신 상태 UPSERT
- [**Receiver DB 설계 요약 (`db/receiver-db.md`)**](db/receiver-db.md)  
  - `received_event`, `quality_case` 등 3개 테이블 정의서 및 ERD
  - `ON CONFLICT DO NOTHING` 기반 멱등성 보장 및 S12 비교 실험용 naive 테이블

### 3. 개발 표준 및 컨벤션 ([`convention/`](convention/))
- [**개발 컨벤션 가이드 (`convention/conventions.md`)**](convention/conventions.md)  
  - Java 17 및 기능 중심(Feature-driven) 패키지 네이밍 규칙
  - HTTP 상태 코드 규약 (`200`, `202`, `409`, `422`) 및 Problem Details 포맷
  - PostgreSQL & MyBatis SQL 작성 수칙 및 제약조건 네이밍
  - Testcontainers 기반 통합 테스트 및 Conventional Commits 규약

### 4. 구현 계획 및 구현 내용 ([`plan/`](plan/))

구현 순서대로 정리했습니다. 수신 처리 순서는 형식 검증(IN-01) → 정규화(IN-04) → 중복 판정(IN-05) → 공통 규칙(IN-06) → 브랜드 정책(IN-07) → 저장(IN-08)입니다.

- [**IN-02 공통 오류 응답 (RFC 9457) (`plan/gateway/01-ingest-design/in-02-error-response.md`)**](plan/gateway/01-ingest-design/in-02-error-response.md)
  - `BusinessException` 확장 속성(`policyCode` 등) 지원 및 예약 키 충돌 방지
  - DB 연결 장애 시 `503 Service Unavailable` 변환 및 `GatewayExceptionAdvice` 설계
- [**IN-01 수신 API·요청 DTO (`plan/gateway/01-ingest-design/in-01-ingest-api.md`)**](plan/gateway/01-ingest-design/in-01-ingest-api.md)
  - `POST /api/v1/diagnostic-results`, Bean Validation 형식 검증과 `400 invalid-request`
  - `occurredAt` 오프셋 필수·숫자 epoch 거부
- [**IN-04 정규화·payload_hash (`plan/gateway/01-ingest-design/in-04-payload-hash.md`)**](plan/gateway/01-ingest-design/in-04-payload-hash.md)
  - UTC 변환·ECU/DTC 정렬, 표현만 다른 재전송이 같은 SHA-256 hash가 되도록 정규화
- [**IN-05 중복 수신 판정 (`plan/gateway/01-ingest-design/in-05-duplicate-check.md`)**](plan/gateway/01-ingest-design/in-05-duplicate-check.md)
  - `(device_id, event_id)` 사전 조회, 같은 hash면 재전송(`DUPLICATE`), 다르면 `409 event-conflict`
- [**IN-07 브랜드 정책 (`plan/gateway/01-ingest-design/in-07-brand-policy.md`)**](plan/gateway/01-ingest-design/in-07-brand-policy.md)
  - `STANDARD-v1`·`BRAND_A-v1` 정책과 `PolicyRegistry`, 위반 시 `422 policy-violation` + `policyCode`



### 5. 수신 기능 전체 현황과 발표 자료

- [IN-01~12 구현 현황 및 테스트 결과](plan/gateway/01-ingest-design/README.md)
- [IN-06 공통 규칙](plan/gateway/01-ingest-design/in-06-common-rule.md)
- [IN-10 전송 본문 생성](plan/gateway/01-ingest-design/in-10-outbox-payload.md)
- [IN-08 저장 트랜잭션](plan/gateway/01-ingest-design/in-08-save-transaction.md)
- [IN-09 최신 상태 갱신](plan/gateway/01-ingest-design/in-09-latest-upsert.md)
- [IN-11 수신 응답](plan/gateway/01-ingest-design/in-11-ingest-response.md)
- [IN-03 요청 추적 로그](plan/gateway/01-ingest-design/in-03-request-log.md)
- [IN-12 결과 조회](plan/gateway/01-ingest-design/in-12-result-query.md)
- [일반 설명용 HTML 슬라이드 (9장)](flow/gateway-overview.html)
- [개발자용 HTML 슬라이드 (13장)](flow/gateway-developer.html)

### 6. Receiver 기능과 발표 자료

- [RC-01~10 구현 현황 및 검증 기록](plan/receiver/03-receiver-design/README.md)
- [Receiver 일반 설명용 슬라이드](flow/receiver-overview.html): 품질 건 반영, 재전송·응답 유실, 장애 모드와 naive 비교
- [Receiver 개발자용 슬라이드](flow/receiver-developer.html): API·검증·hash, 원자적 저장과 동시성, 장애 주입·로그·실행·검증

- [운영 API·장애 시나리오 구현](plan/gateway/04-ops-verify-design/README.md)
  - 멀티 프로젝트, 실제 receiver 테스트 기반, 정합성 대조, 운영 API와 재처리
- [시나리오 검증 결과](results.md)
  - S1~S5·S7~S10·S12 실제 수치와 기본/비교 모드 대조
