# 프로파일링 RabbitMQ 기반 설정 — PR 3-1

관련 이슈: #525 / 상위 #522. 자동 작업 생성·발행과 결과 Listener는 아직 연결하지 않는다.

## 로컬 실행

`.env.example`의 RabbitMQ 항목을 로컬 `.env`에 설정한다. Compose는 `.env`를 읽지만 BE에는 별도로 실행 환경변수를 전달해야 한다.

```bash
docker compose -f compose.rabbitmq.yaml up -d --wait
```

기본 vhost `profiling` 기준으로 요청 큐 보호 정책을 적용한다. vhost를 변경했다면 `-p`도 변경한다.

```bash
docker compose -f compose.rabbitmq.yaml exec rabbitmq rabbitmqctl set_policy \
  -p profiling --apply-to queues profiling-request-protection '^profiling[.]request$' \
  '{"max-length":10000,"max-length-bytes":268435456,"overflow":"reject-publish"}'
```

길이 제한은 브로커 Policy에서 관리하며 Java 큐 인자로 중복 지정하지 않는다. 요청 TTL·지연 재시도 큐는 미설정이다. 상한은 브로커 전체 메모리 제한이 아니며 unacked를 포함하지 않는다. 결과 큐에는 같은 상한을 임의 적용하지 않는다.

BE 실행 환경에 `AI_PROFILE_AMQP_ENABLED=true`와 접속 정보를 전달하면 RabbitAdmin이 선언한다. 비활성 기본값에서는 프로파일링 전용 Bean을 생성하지 않는다. 공통 Boot 연결·기본 템플릿 Bean은 존재할 수 있지만 두 기능이 모두 OFF이면 Admin 선언과 Rabbit health 검사를 수행하지 않는다.

- RabbitMQ 4.4.0-management, 고정 노드 이름·데이터 볼륨
- Direct Exchange `profiling.exchange`
- Quorum 큐·Routing Key `profiling.request`, `profiling.result`
- Durable·exclusive=false·auto-delete=false, Persistent JSON/UTF-8
- 공통 `rabbitConnectionFactory`를 Outbox·프로파일링 템플릿과 Admin이 명시적으로 주입받는다. 프로파일링 템플릿은 별도 발행 연결을 사용한다.
- 연결 3초·Heartbeat 요청 20초·프로파일링 Confirm 대기 설정 30초이며 실제 발행에서는 Confirm을 최대 30초 대기한다. Outbox Confirm 대기는 기존 발행 코드의 정책을 유지한다.
- 기본 멤버 1은 로컬용이며 AZ 장애 대응이나 복제 가용성을 보장하지 않는다

```bash
docker compose -f compose.rabbitmq.yaml exec rabbitmq rabbitmqctl list_queues -p profiling name type durable arguments
docker compose -f compose.rabbitmq.yaml exec rabbitmq rabbitmqctl list_bindings -p profiling source_name destination_name routing_key
docker compose -f compose.rabbitmq.yaml exec rabbitmq rabbitmqctl list_policies -p profiling
```

기존 Classic 큐와 이름·인자가 충돌해도 자동 삭제하지 않는다. 운영 TLS·권한·3 AZ 배치와 멤버 장애 검증은 별도 배포 범위다.

## 검증

```bash
./gradlew :main-backend:test --tests '*ProfilingAmqp*Test'
```

설정 테스트는 브로커 없이 실행한다. 실제 브로커 통합 테스트는 Testcontainers로 격리된 RabbitMQ 4.4를 띄워 Policy·Quorum 선언·Binding·Persistent·Confirm·Return·연결 재생성을 검증한다. 개발용 브로커나 업무 메시지는 건드리지 않는다.

CI는 같은 테스트 작업에서 RabbitMQ 컨테이너를 실행한다. Docker 기동 smoke test는 AMQP 비활성 기본 경로를 유지한다. 테스트 HTML·JUnit XML은 성공·실패 모두 artifact로 남긴다.

## 공통 연결 통합

- BE 접속 설정은 `spring.rabbitmq` / `SPRING_RABBITMQ_*` 한 곳에서 관리한다. 이전 `app.ai-profile.amqp.host/port/username/password/virtual-host`는 제거했다.
- Compose는 `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `RABBITMQ_VHOST`를 사용한다. BE의 대응 값과 같은 접속 대상을 지정한다. BE가 컨테이너 안에서 실행되면 host는 해당 네트워크 주소를 사용한다.
- 기존 계정·vhost `profiling`을 그대로 공유하는 예시이며, 기존 볼륨·큐·메시지를 삭제하지 않는다. 기존 볼륨의 계정·vhost는 환경변수만 바꿔 갱신되지 않는다.
- Boot의 자동 연결 생성을 사용하므로 `spring.rabbitmq.addresses`·TLS 설정을 별도의 수동 복사 없이 유지한다. `RabbitConnectionConfiguration`은 클라이언트 I/O 제한과 Spring 연결 종료·채널 확보 대기만 보완한다.
- Handshake·Channel RPC·Shutdown·채널 확보·연결 종료 대기는 각 3초, NIO 쓰기 큐는 64프레임·삽입 대기 3초다. 공유 채널 캐시는 Boot 기본 25개이며 `spring.rabbitmq.cache.channel.size`로 조정할 수 있다. 프로파일링 워커는 인스턴스당 1개로 순차 발행한다. 운영 적정 용량은 별도 부하 측정 대상이다.
- `RabbitPublisherConfiguration`·`RabbitRoutingProperties`는 원격 develop `6093c9b`의 공통 Publisher 설정을 반영하고, 연결 주입에 `@Qualifier("rabbitConnectionFactory")`를 지정했다. 이 변경에서 공통 Relay·업무 Consumer를 새로 구현하지 않는다.
- `OUTBOX_ENABLED=true`인 경우 기존 공통 Outbox의 Exchange·알림/검색 Routing Key를 설정해야 한다. 관련 값은 `.env.example`을 따른다. 프로파일링의 Direct Exchange·Quorum 큐와 작업 테이블·재시도 정책은 유지한다.
- `spring.rabbitmq.dynamic=false`로 기본 Admin 자동 선언을 끄고, 프로파일링 Admin은 자신에게 지정된 리소스만 선언한다. 다른 도메인도 별도 Admin/인프라에서 해당 리소스를 선언해야 한다.
- Rabbit health는 `OUTBOX_ENABLED OR AI_PROFILE_AMQP_ENABLED`일 때만 공통 연결을 확인한다. 기본 health 자동 구성은 비활성화하고 동일한 표준 `RabbitHealthIndicator`를 조건부 등록한다.

| Outbox | 프로파일링 | 동작 |
| --- | --- | --- |
| OFF | OFF | 도메인 발행·선언·Rabbit health 검사 없음 |
| ON | OFF | Outbox 템플릿과 Rabbit health 활성화 |
| OFF | ON | 프로파일링 템플릿·Admin·Rabbit health 활성화 |
| ON | ON | 두 템플릿이 같은 공통 host·계정·vhost 사용 |

```bash
./gradlew :main-backend:test --tests '*RabbitConnectionConfigurationTest' --tests '*ProfilingAmqp*Test'
```

네 조합에서 잘못된 기존 프로파일링 접속 속성이 남아 있어도 공통 접속 대상이 바뀌지 않는지 검증한다. 실제 RabbitMQ 4.4에서는 공통 연결로 Outbox Topic/Classic과 프로파일링 Direct/Quorum을 함께 선언·발행하고 Confirm·Return·재접속을 검증한다. 두 템플릿에서 각각 10건을 병렬 발행해 대상 큐·메시지 ID·본문과 Confirm이 서로 섞이지 않는지 확인한다. 두 기능을 함께 켰을 때 Outbox Exchange·Routing Key가 비어 있으면 기동 시 거부하는 경우도 검증한다. 운영 계정 권한·TLS 연결·3노드 장애 전환·미포함 공통 Relay 회귀는 별도 검증 범위다.

## PR 3-2 — 발행 선점·전달 확인·재시도

- `ProfilingPublishStore`가 DB UTC 시각으로 WAITING 상태의 READY·기한 도래 RETRY_WAIT 작업을 선점한다. 선점 2분·시도별 UUID 토큰을 사용하고, 만료 선점은 실패로 회수한다.
- `ProfilingJobPublisher.publishOne()`은 DB 트랜잭션 밖에서 호출한다. 저장 JSON을 UTF-8로 재사용하고 256 KiB를 검증하며 requestId를 messageId로, 시도 토큰을 CorrelationData ID로 사용한다.
- Confirm 최대 30초·ACK이며 Return이 없는 경우만 PUBLISHED로 기록한다. 전체 발행 작업의 대기는 45초이며 실제 워커 종료까지 새 선점을 제한한다. 실행 전 취소 시에도 busy가 해제된다.
- 일시 실패는 최초 포함 최대 3회, 1분 → 2분의 ±20% 지터다. 최초 실패 후 30분 예산 소진 또는 인증·라우팅 오류는 BLOCKED이며 자동 재선점하지 않는다.
- 결과 저장은 요청 버전·선점 토큰·유효 임대·WAITING 상태를 확인한다. 늦은 이전 시도의 확인은 무시한다. 발행 성공 후 DB 기록 실패는 임대 만료 후 동일 본문으로 복구한다. Consumer 멱등 처리는 별도로 필요하다.
- 브로커 차단·연결 교체·종료를 관측한다. 이전 연결 이벤트는 무시하고 미전송 선점 반납 시 이전 시도 횟수·재시도 시각을 복원한다.
- PR 3-2에서는 발행 단위까지 제공하며, 스케줄러 연결은 아래 PR 3-3에서 적용한다.

```bash
./gradlew :main-backend:test --tests '*ProfilingPublish*Test' --tests '*ProfilingJobPublisherTest' --tests '*ProfilingAmqp*Test'
```

공통 연결 수정 PR #553이 병합된 develop을 기준으로 구현한다. 이 변경에는 공통 접속 설정 수정이 중복 포함되지 않는다.


## PR 3-3 — MQ 스케줄러 전환

- `AI_PROFILE_SCHEDULING_ENABLED=true`와 `AI_PROFILE_AMQP_ENABLED=true`를 함께 설정해야 자동 실행한다. 공통 `SCHEDULING_ENABLED=true`도 필요하다. 애플리케이션 기본값은 둘 다 false이며, 예제는 scheduling=true·AMQP=false여서 자동 발행하지 않는다. 기능 활성화 전에 AI 요청 Consumer와 결과 수신 경로를 준비한다.
- 기존 `ProfileDispatchScheduler` → `ProfileDispatchService` → 작업 준비 → 발행기 흐름을 사용한다. AI `/health` 확인·HTTP POST·오래된 PENDING의 HTTP 재요청은 호출하지 않는다.
- 후보 조회와 준비 트랜잭션에서 최신 버전의 READY·CLAIMED·RETRY_WAIT를 확인한다. 이 작업이 먼저 발행될 때까지 신규 변경은 보존한다. PUBLISHED 또는 BLOCKED 이후 신규 변경은 다음 버전으로 준비할 수 있으며 BLOCKED 작업은 SUPERSEDED로 남긴다.
- 한 틱에서 신규 준비 최대 100개, 신규·재시도를 합친 발행 호출 최대 100회다. 설정은 1~100만 허용한다. 복구 전용 배치 크기·기존 프로필 선점 타임아웃 설정은 제거한다. 작업별 선점 2분은 PR 3-2 발행 저장소에서 관리한다.
- 고정 지연 기본값 1분은 **이전 틱 종료 후** 대기 시간이다. 인스턴스별 잠금으로 같은 인스턴스의 틱 중첩을 막는다. 엄밀한 전체 시스템 100건/분 제한은 아니며 다중 인스턴스의 총 발행량은 별도 제어가 필요하다.
- 변경 후보가 없어도 발행 루프를 실행하여 READY·기한 도래 RETRY_WAIT·만료 CLAIMED를 처리한다. NO_WORK·STOP·인터럽트에서는 루프를 종료한다. PUBLISHED의 결과 지연만으로 요청을 재발행하지 않으며 BLOCKED는 자동 복구하지 않는다.
- 기존 HTTP 콜백 API·추천 저장은 유지한다. 콜백은 현재 프로필·추천을 반영하지만 `profiling_jobs.result_status`를 갱신하지 않는다. requestId 기반 성공·최종 실패 결과 Consumer와 작업 종료 기록은 후속 범위다.

### V51 적용 전 확인

`db.migration.V51__remove_legacy_profile_dispatch_fields`는 Flyway Java 마이그레이션이다. 프로필의 재시도·선점 4개 컬럼과 관련 CHECK·PENDING 타임아웃 인덱스만 제거한다. 프로필·추천·작업 데이터 및 FK는 유지한다.

1. 기존 HTTP 발송 애플리케이션 인스턴스를 모두 종료하고 DB를 백업한다. 컬럼 제거와 구버전 애플리케이션의 동시 운용은 지원하지 않는다.
2. 아래 조회 결과가 없어야 적용한다. 남아 있으면 이전 콜백 완료를 기다리거나 대상을 확인해 수동 정리한다. 마이그레이션이 임의로 새 요청을 만들거나 기존 상태를 초기화하지 않는다.
3. Java 마이그레이션도 같은 조건을 검사하고 남은 행이 있으면 컬럼 제거 전에 실패시킨다. MySQL DDL의 자동 롤백을 가정하지 않는다. 실패 후에는 원인·스키마·Flyway 이력을 확인한 뒤 복구한다.

```sql
SELECT p.id, p.recipient_id, p.profile_status, p.source_version,
       p.dispatch_claim_token, p.dispatch_claimed_at
FROM recipient_profiles p
WHERE p.dispatch_claim_token IS NOT NULL
   OR p.dispatch_claimed_at IS NOT NULL
   OR p.dispatch_claim_last_changed_at IS NOT NULL
   OR (p.profile_status = 'PENDING' AND NOT EXISTS (
       SELECT 1 FROM profiling_jobs j
       WHERE j.profile_id = p.id AND j.source_version = p.source_version
   ));
```

```bash
./gradlew :main-backend:test --tests '*ProfileDispatch*Test' --tests '*ProfilingJobPreparationIntegrationTest' --tests '*RecipientProfile*Test' --tests '*AiProfilingPropertiesTest'
```

테스트는 격리된 MySQL 8.4와 RabbitMQ 4.4에서 디바운스·커밋 후 발행·저장 JSON 동일성·기존 작업 재시도·만료 선점 회수·BLOCKED/PUBLISHED 재발행 차단을 확인한다. 마이그레이션 테스트는 기존 HTTP 진행 작업 거부와 프로필·추천·작업 보존을 확인한다.
