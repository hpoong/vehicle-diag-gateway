# README 도식 이미지

프로젝트 소개 및 구현 상세 문서에서 사용하는 PNG의 원본은 [diagrams.html](diagrams.html)입니다.
외부 폰트·라이브러리·Mermaid 없이 HTML/CSS로 그린 뒤 Chrome에서 캡처했습니다.
PNG는 CSS 너비 1120px, 배율 2로 저장했습니다. README에서는 본문 너비에 맞춰 축소됩니다.

## 도식 선택

HTML을 브라우저에서 열고 URL 끝에 다음 쿼리를 붙이면 해당 도식이 표시됩니다.
쿼리가 없으면 프로젝트 소개를 표시합니다.

| PNG | URL 쿼리 | CSS 크기 |
| --- | --- | --- |
| `project-overview.png` | `?diagram=project-overview` | 1120 × 596 |
| `project-features.png` | `?diagram=project-features` | 1120 × 649 |
| `architecture.png` | `?diagram=architecture` | 1120 × 715 |
| `acceptance.png` | `?diagram=acceptance` | 1120 × 557 |
| `response-loss.png` | `?diagram=response-loss` | 1120 × 715 |
| `recovery.png` | `?diagram=recovery` | 1120 × 673 |
| `comparison.png` | `?diagram=comparison` | 1120 × 623 |

현재 루트 README는 `project-overview.png`와 `project-features.png`를 본문에 표시합니다. 나머지 도식은 구현 상세 문서에서 사용합니다.

## 수정 및 다시 캡처

1. `diagrams.html`에서 해당 `section`과 공통 CSS를 수정합니다.
2. Chrome에서 원하는 도식을 열고 폰트 로딩이 끝날 때까지 기다립니다.
3. 개발자 도구의 기기 도구 모음에서 너비 1120px, DPR 2로 설정합니다.
4. 실제 도식 높이에 맞춰 뷰포트 높이를 설정하고 전체 페이지를 PNG로 캡처합니다.
5. 같은 파일명으로 교체하고 루트 README에서 글자·화살표·여백을 확인합니다.

비교 이미지의 수치는 [검증 결과](../../results.md)의 2026-11-07 실행 기록을 인용합니다.
검증 결과가 바뀌면 HTML 설명과 캡처 이미지도 함께 갱신합니다.
