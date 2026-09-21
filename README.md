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

## Postman

`docs/postman`의 파일을 Postman에서 Import하여 로컬 서버를 테스트합니다.

- `gift-api.postman_collection.json`: API 요청 컬렉션
- `gift-local.postman_environment.json`: 로컬 환경 변수 (`baseUrl`, `email`, `giftId`, `productId`, `origin`, `accessToken`)

1. `.env`의 DB에 해당하는 MySQL을 실행하고 애플리케이션을 `http://localhost:8080`에서 실행합니다.
2. Postman에서 두 파일을 Import하고 Environment를 `Gift Local`로 선택합니다.
3. 회원가입은 `약관 조회`를 먼저 실행해야 합니다. 응답의 약관에 모두 동의하는 값이 자동으로 채워집니다.
   같은 `email`로 다시 가입하면 중복 오류가 발생하므로 Environment의 `email`을 바꿉니다.

4. `gifts`, `friends` 폴더와 `products`의 `상품 카테고리 목록 조회`, `상품 상세 조회`는 인증이 필요합니다. `auth`의 `로그인`을 먼저 실행하면 응답의 `accessToken`이
   Environment의 `accessToken`에 저장되어 자동으로 사용됩니다. 로그인하지 않으면 401 Unauthorized를 반환합니다.
   `로그인`은 `회원가입`에 사용한 `email`과 같은 계정으로 요청하므로 회원가입을 먼저 실행해야 합니다.
5. `토큰 재발급`, `로그아웃`은 로그인 응답의 `refreshToken` Cookie와 `Origin` 헤더가 필요합니다. `origin` 변수의 값은
   `.env`의 `CORS_ALLOWED_ORIGINS`에 포함된 값이어야 하며, 아니면 403이 발생합니다.
