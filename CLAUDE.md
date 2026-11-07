# Tasty Android

Tasty의 Android 앱 소스 레포다. 공개 레포다.

## 역할

- 모바일은 데스크톱 Tasty와 다른 형태로 만든다. 데스크톱의 제품 원칙을 그대로 적용하지 않으며, 원칙은 따로 정한다.
- 플러그인 시스템을 지원하지 않는다. 일부 공식 플러그인 기능만 앱에 내장한다.
- 내장할 기능과 그 밖의 구체적인 범위, 기술 스택은 아직 정하지 않았다.

## Git 규칙

- [Conventional Commits](https://www.conventionalcommits.org/)를 따른다: `type(scope): description`. type은 `feat`, `fix`, `docs`, `refactor`, `test`, `perf`, `style`, `i18n`, `chore` 중에서 고른다.
- 제목과 본문은 영어 평문으로 쓰고 Markdown 서식을 쓰지 않는다.
- 한 커밋에는 한 변경만 담는다. 기능 수정이나 추가가 끝나면 다시 묻지 않고 커밋한다. 리팩토링과 기능 추가는 나눈다.
- `--no-verify`를 쓰지 않는다. 훅이 막으면 우회하지 말고 고치거나 보고한다.
- `.claude`와 `.claude-workspace`는 git에 넣지 않는다. 로컬 경로와 로컬 작업 문서를 이 레포에 적지 않는다.
- 자세한 기준은 [커밋 컨벤션](https://github.com/zilhak/tasty/blob/main/docs/dev-guide/commit-convention.md)을 따른다.
