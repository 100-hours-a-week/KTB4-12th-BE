# KTB4-12th-BE
KTB4 12th team backend application

## IntelliJ IDEA formatter

프로젝트에 포함된 `.editorconfig`와 Project Code Style을 사용합니다.

Actions on Save는 개인 IDE 설정이므로 각 팀원이
`Settings > Tools > Actions on Save`에서 다음과 같이 설정합니다.

- Reformat code: ON
- Optimize imports: ON
- Rearrange code: OFF
- Code cleanup: OFF

## Git hooks

프로젝트를 처음 clone한 뒤 공유 Git Hook을 활성화합니다.

```bash
git config core.hooksPath .githooks
```

pre-commit Hook은 commit 전에 `./gradlew build`를 실행하며, 빌드가 실패하면 commit을 중단합니다.
