# KTB4-12th-BE
KTB4 12th team backend application

## Gradle modules

```text
backend
├── build.gradle
├── settings.gradle
├── main-backend
│   ├── build.gradle
│   ├── Dockerfile
│   └── src
└── payment-server
    ├── build.gradle
    ├── Dockerfile
    └── src
```

저장소 루트에서 전체 프로젝트를 빌드할 수 있습니다.

```bash
./gradlew build
```

각 애플리케이션을 실행하거나 테스트할 때에는 모듈 경로를 지정합니다.

```bash
./gradlew :main-backend:bootRun
./gradlew :main-backend:test

./gradlew :payment-server:bootRun
./gradlew :payment-server:test
```

Main Backend는 기본 포트 `8080`, Payment Server는 기본 포트 `8090`을 사용합니다.

## Docker

Docker 빌드 Context는 두 이미지 모두 저장소 루트를 사용합니다.

```bash
docker build \
  --file main-backend/Dockerfile \
  --tag seonjalal-main-backend:local \
  .

docker build \
  --file payment-server/Dockerfile \
  --tag seonjalal-payment-server:local \
  .
```

Health Check 경로는 두 애플리케이션 모두 `/actuator/health`입니다. Payment Server는 외부에
공개하지 않고 Main Backend가 접근할 수 있는 내부 네트워크에서 실행합니다.

## 상품 검색 배포 설정

현재 배포는 `PRODUCT_SEARCH_ENABLED=false`로 기존 MySQL 검색을 사용합니다.
Main Backend 이미지의 기본값과 CI 검증 컨테이너에도 `false`를 적용하며 ES Health 검사는 제외합니다.
서버의 Docker/Compose/SSM 환경 설정에서 이 값을 덮어쓰는 경우에도 `false`로 유지해야 합니다.
실제 서버의 SSM 배포 문서는 이 저장소에서 관리하지 않으므로 배포 담당자가 주입값을 확인해야 합니다.

로컬 ES 검증 시에만 `PRODUCT_SEARCH_ENABLED=true`와 `ELASTICSEARCH_URIS`를 지정합니다.
운영 활성화는 최신 상품 적재·변경 동기화·운영 검증을 완료한 후 별도로 결정합니다.

ES 사용 시 `PRODUCT_SEARCH_INDEX`로 서비스 DB에 대응하는 인덱스를 지정합니다.
초기 매핑은 `main-backend/src/main/resources/elasticsearch/products-index.json`에 있으며,
현재 로컬 검증 인덱스는 `gift-products-local-v1`입니다. 인덱스 생성·상품 적재는 자동 실행하지 않습니다.
현재 적재 데이터의 MySQL DATETIME은 한국 시간(+09:00)으로 해석하며 커서도 같은 기준으로 변환합니다.

ES는 두 글자 이상 일반 상품 검색에만 사용합니다. 검색어 없음·한 글자·AI 추천 요청은 기존 경로를 유지합니다.
활성화된 ES 검색이 실패하면 503을 반환하며 MySQL로 자동 재검색하지 않습니다.
변경 동기화는 별도 작업자를 활성화해야 동작하며 비동기로 반영됩니다.
PIT는 적용하지 않아 데이터 갱신 중에는 검색 결과·페이지 순서가 달라질 수 있습니다.
모든 부분 문자열·영문 대소문자·특수문자에 대한 기존 검색과의 동등성은 추가 검증이 필요합니다.

ES가 없어도 실행되는 검증은 일반 테스트에 포함됩니다. 실제 ES 통합 테스트는 해당 인덱스에
상품을 적재한 로컬 환경에서만 다음과 같이 실행합니다.

```bash
RUN_LOCAL_ES_TEST=true ./gradlew :main-backend:test \
  --tests 'com.gift.gift.domain.product.search.ProductSearchRepositoryIntegrationTest'
```

## 상품 검색 Outbox 기록 설정

`PRODUCT_SEARCH_OUTBOX_ENABLED=false`가 기본값입니다. 운영 환경에서는
`PRODUCT_SEARCH_ENABLED=false`와 함께 유지합니다. Outbox 비활성 상태에서는
동기화 작업을 기록하지 않으며 ES 연결도 필요하지 않습니다.

비활성화 여부와 관계없이 Flyway `V34`가 `products.search_version`과
`product_search_outbox` 테이블을 추가합니다. 조회수 배치 집계·재고 차감·재고 복구가
실제 성공하면 검색 버전은 증가합니다. 배포 시 마이그레이션 실행 권한과 소요 시간을 확인해야 합니다.

활성화하면 위 세 변경 경로에서 상품 변경과 Outbox 기록이 같은 트랜잭션으로 저장됩니다.
조회수는 `product_view_stats.views`, 검색 버전은 `products.search_version`에서 관리합니다.
조회 요청은 이벤트만 저장하며 검색 버전 증가와 Outbox 기록은 실제 배치 집계 트랜잭션에서 수행합니다.
ES 반영 워커는 `PRODUCT_SEARCH_SYNC_ENABLED=false`가 기본값이며 검색 활성화와 별도로 제어합니다.
운영 동기화를 검증하기 전에는 기록과 작업자를 활성화하지 않습니다. 완료·실패 기록 정리는 후속 작업입니다. 직접 SQL·외부 적재 경로의 변경은 기록하지 않으며,
비활성 기간의 변경도 쌓이지 않으므로 ES 검색 활성화 전 최신 데이터 재적재가 필요합니다.

## 상품 검색 동기화 작업자 설정 및 복구

팀원 로컬·현재 배포에서는 다음 세 값을 모두 `false`로 유지합니다. 미설정 시에도 기본값은 `false`이며,
ES 실행이나 추가 환경변수 없이 기존 MySQL 검색을 사용합니다. 이번 PR에는 추가 DB 마이그레이션이 없습니다.

```dotenv
PRODUCT_SEARCH_ENABLED=false
PRODUCT_SEARCH_OUTBOX_ENABLED=false
PRODUCT_SEARCH_SYNC_ENABLED=false
```

동기화 검증 환경에서는 서비스 DB에 대응하는 인덱스를 먼저 생성·적재하고, 다음 추가 매핑을 적용합니다.
`products-index.json`은 `dynamic: strict`이므로 이 단계 없이 작업자를 켜면 `search_version` 색인이 실패합니다.
아래 명령은 저장소 루트에서 실행하며 인증이 필요한 ES에서는 인증·TLS 옵션을 함께 지정합니다.

```bash
curl --fail-with-body -X PUT \
  "${ELASTICSEARCH_URIS:-http://127.0.0.1:9201}/${PRODUCT_SEARCH_INDEX:-gift-products-local-v1}/_mapping" \
  -H 'Content-Type: application/json' \
  --data-binary @main-backend/src/main/resources/elasticsearch/products-sync-mapping.json
```

이후 `PRODUCT_SEARCH_OUTBOX_ENABLED=true`와 `PRODUCT_SEARCH_SYNC_ENABLED=true`를 지정하고 재시작합니다.
검색 전환 없이 동기화만 검증하려면 `PRODUCT_SEARCH_ENABLED=false`를 유지합니다.
`SCHEDULING_ENABLED=false`이면 자동 실행하지 않습니다. 폴링·배치·임대·재시도 설정은 `.env.example`을 따릅니다.
검색 노출은 ES refresh 이후이므로 Bulk 성공 즉시 검색에 보이는 것은 아닙니다.
운영 최초 적재와 변경분 연결·검색 전환은 별도 검증 후 진행합니다.

- 일시적 실패는 지수 백오프로 재시도합니다. 작업자가 중단되면 임대 만료 후 다른 실행에서 회수합니다.
- 재시도 한도 초과 또는 재시도 불가능한 오류는 `FAILED`로 보존하고 자동 재시도를 중단합니다.
  `last_error_type`으로 원인을 확인·해결한 뒤 아래처럼 **확인한 작업 ID만** 재등록합니다.
  `:job_id`는 실제 작업 ID로 교체하며 이 SQL은 자동 실행하지 않습니다.

```sql
UPDATE product_search_outbox
SET status = 'PENDING', failure_count = 0,
    next_attempt_at = CURRENT_TIMESTAMP(6), last_error_type = NULL,
    claim_token = NULL, lease_until = NULL, processed_at = NULL,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE id = :job_id AND status = 'FAILED';
```

삭제는 Outbox 작업에 대응하는 원본의 삭제 상태를 반영합니다. 삭제 변경 자체를 기록하지 않는 경로는 자동 감지하지 않습니다.
물리 삭제 상품은 최대 버전의 삭제 표시 문서를 남겨 과거 작업에 의한 복원을 막습니다.
따라서 상품 ID를 재사용하거나 삭제 표시 문서를 임의로 제거하지 않습니다.
현재 기록 경로는 조회수 배치 집계·재고 차감·재고 복구이며, 다른 변경 경로의 기록과 완료 기록 정리는 후속 범위입니다.

## 상품 검색 동기화 테스트

단위 테스트와 MySQL 통합 테스트는 일반 테스트에 포함됩니다. MySQL은 Testcontainers를 사용하므로
로컬 서비스 DB를 변경하지 않습니다. 실제 ES 테스트는 기본 제외하며 다음 명령으로 별도 실행합니다.

```bash
RUN_LOCAL_ES_SYNC_TEST=true ELASTICSEARCH_URIS=http://127.0.0.1:9201 \
./gradlew :main-backend:test \
  --tests 'com.gift.gift.domain.product.search.ProductSearchSyncElasticsearchTest'
```

ES 테스트는 UUID가 포함된 `test-product-sync-*` 인덱스를 생성하고 종료 시 해당 인덱스만 삭제합니다.
서비스 인덱스에는 쓰지 않습니다. 작업 선점·임대 회수·독립 트랜잭션·재시도 한도·부분 실패와
최신 조회수·중복·역순·삭제·소프트 삭제 복구를 검증합니다.

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
