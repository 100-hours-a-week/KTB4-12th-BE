# 상품 상세 캐시 기본 검증 — 1~5단계

실행일: 2026-11-01 (KST), 대상: 로컬 463 구현.

## 결과

관련 5개 테스트 클래스 **25건 통과**, 실패·오류·건너뜀 0건.
신규 `ProductDetailCacheIntegrationTest` 11건을 포함한다.
테스트 JVM 전용 MySQL 8.4 및 Redis 7.4 컨테이너를 사용했다.
서비스용 로컬 DB·Redis를 변경하거나 재시작하지 않았다.
1~5단계 검증에서는 테스트와 이 기록을 추가했다. PR 준비 시 최신 develop을 반영하고,
상품 캐시는 기존 infrastructure.redis.RedisConfig의 productRedisTemplate을 재사용하도록 맞췄다.

| 단계 | 확인한 동작 | 근거 |
|---|---|---|
| 1. 기본 캐시 | 실제 Redis 미스 시 DB 적재, 히트 시 메타데이터 조회 생략, Hash 타입, 설정 TTL 2시간, 만료된 키 재적재 | 통합 테스트 |
| 2. 응답·최신 상태 | 재고 0 변경 반영, 삭제 상품 예외 및 캐시 읽기 차단, null 설명·빈 이미지·BigDecimal 복원, Hash 교체 시 이전 설명 제거, DB 이미지 순서와 응답 URL 보존 | 통합·단위 테스트 |
| 3. 조회 이력 | 캐시 적중 중 최초 이벤트 1건, 24시간 내 재조회 중복 미저장, 25시간 경과 후 추가 이벤트, 배치 전 조회수 통계 미갱신 | 실제 MySQL·Redis 통합 테스트 |
| 4. 커밋 후 무효화 | 테스트용 가격·이미지 변경 이벤트 발행, 커밋 후 삭제 및 변경 데이터 재적재, 롤백 시 기존 DB·캐시 유지, 트랜잭션 없는 이벤트 무시 | 통합·단위 테스트 |
| 5. 장애 처리 | 읽기·쓰기 예외에도 DB 결과 반환, 읽기 실패 후 재저장 미시도, 실제 테스트 Redis 일시 정지 후 명령 타임아웃·DB fallback, 삭제 일시 실패 재시도 및 3회 실패 종료 | 통합·단위 테스트 |

## 변경 이벤트의 검증 범위

현재 실제 상품 수정 이벤트 발행 경로가 없다는 작업자 확인에 따라 수정 API를 추가하지 않았다.
테스트에서 `TransactionTemplate`으로 DB 값을 변경하고
`ProductMetadataChangedEvent`를 직접 발행했다.
따라서 검증 결과는 **발행된 이벤트의 트랜잭션 연동 및 처리 계약**이며,
현재 서비스의 모든 변경 경로가 자동으로 이벤트를 발행한다는 의미가 아니다.

Redis 읽기·쓰기 예외는 repository spy로 주입했다.
이와 별도로 테스트 Redis 컨테이너만 일시 정지하여 실제 연결의 명령 타임아웃 후 fallback도 확인했다.
인증·상품 연결에는 같은 격리 Redis를 할당했으므로 두 인스턴스 장애 격리는 이 실행의 검증 범위가 아니다.
이미지 URL 생성기는 mock이며 실제 S3 서명 요청·이미지 전송·HTTP 오류 봉투는 별도 검증 범위다.

## 실행 명령

저장소 `gift_BE`에서 Docker 실행 상태로 수행한다. 로컬 백엔드 실행은 필요하지 않다.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./gradlew :main-backend:test \
  --tests '*ProductDetailCacheServiceTest' \
  --tests '*ProductDetailCacheRepositoryTest' \
  --tests '*ProductDetailCacheEvictionHandlerTest' \
  --tests '*ProductDetailServiceTest' \
  --tests '*ProductDetailCacheIntegrationTest' \
  --console=plain
```

| 클래스 | 건수 |
|---|---:|
| ProductDetailCacheServiceTest | 5 |
| ProductDetailCacheRepositoryTest | 2 |
| ProductDetailCacheEvictionHandlerTest | 5 |
| ProductDetailServiceTest | 2 |
| ProductDetailCacheIntegrationTest | 11 |

Gradle 종료 코드 0, `BUILD SUCCESSFUL`. `git diff --check` 통과.
기존 단위 테스트는 변경하지 않았다. 최초 신규 통합 실행의 3건 실패는
DB의 `DECIMAL(12,0)` 가격에 소수 금액을 넣은 fixture 오류였으며,
DB fixture를 정수 금액으로 수정한 뒤 최종 실행에서 25건 모두 통과했다.
Hash의 BigDecimal 소수 직렬화는 DB와 별도로 round-trip 검증했다.

## 남은 범위

- 무효화 3회 실패 후 로그만으로는 복구를 보장하지 않는다. 재시작 후 영속 재시도 및 엄격한 가격 최신성은 미검증이다.
- 콜드 캐시 동시 미스, Redisson 락, 다중 인스턴스 및 수정/삭제와 재적재 경쟁은 #501에서 수행한다.
- 실제 서비스 변경 경로가 추가되면 이벤트 발행을 연동하고 해당 경로의 커밋·롤백 검증을 추가한다.
- 장애 중 대량 fallback, 메모리/키 퇴출, 인증 기능 회귀, HTTP·S3 종단 검증은 별도 수행한다.
- PR 준비 시 최신 develop 반영 후 main-backend 전체 테스트 1838건을 실행했다. 실패 0건·오류 0건·건너뜀 9건. payment-server 및 새로운 성능 부하 테스트는 실행하지 않았다.
