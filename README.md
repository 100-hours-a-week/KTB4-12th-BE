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

Git은 기본적으로 `.git/hooks`에서 Hook을 찾습니다.
이 프로젝트는 팀에서 공유할 수 있도록 `.githooks`에 Hook을 관리하므로,
`core.hooksPath`를 통해 Git이 해당 디렉터리를 사용하도록 설정해야 합니다.

```bash
git config core.hooksPath .githooks
```

설정값은 현재 저장소의 로컬 Git 설정에만 저장되며 원격 저장소에는 반영되지 않습니다.
따라서 각 팀원이 clone 후 한 번씩 설정해야 합니다.

다음 명령으로 설정 여부를 확인할 수 있습니다.

```bash
git config --get core.hooksPath
```

정상적으로 설정됐다면 `.githooks`가 출력됩니다.

pre-commit Hook은 commit 전에 저장소 루트의 `.env`를 환경변수로 불러온 뒤 `./gradlew build`를 실행합니다.
`.env`가 없다면 현재 Shell에 설정된 환경변수를 사용합니다.

테스트가 MySQL에 연결하므로 commit 전에 `.env`의 DB 설정에 해당하는 MySQL이 실행 중이어야 합니다.
빌드 또는 DB 연결에 실패하면 commit을 중단합니다.
