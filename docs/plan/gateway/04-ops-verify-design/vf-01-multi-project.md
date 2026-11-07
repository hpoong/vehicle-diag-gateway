# VF-01-MULTI-PROJECT Gradle 멀티 프로젝트

## 구현

기존 빌드 구조를 유지했다. `settings.gradle`의 gateway·receiver·web-support와 루트 태스크 선택으로 전체 통합 테스트를 실행한다.

## 검증

`./gradlew projects`, `./gradlew integrationTest --dry-run`으로 세 모듈을 확인했다.
