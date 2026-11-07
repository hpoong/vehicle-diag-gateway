# VF-02-IT-FOUNDATION 통합 테스트 기반

## 구현

gateway 통합 테스트에 receiver의 클래스만 추가하고, 리소스는 빌드 시 `receiver-it/` 접두 경로로 복사한다. 두 서비스의 application.yml·Flyway·MyBatis 경로 충돌을 방지한다. receiver의 런타임 라이브러리는 현재 gateway와 같으며, receiver 전용 의존성 추가 시 테스트 classpath도 함께 갱신해야 한다.

## 검증

`ScenarioEnvironmentIT`: 독립 DB와 마이그레이션 3/1개, 랜덤 포트·chaos 프로필, 수동 전송, DOWN 복구, 시간 조작, 비교 모드 반영을 확인한다.
