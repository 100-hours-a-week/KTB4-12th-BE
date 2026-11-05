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
- 연결 3초·Heartbeat 요청 20초·프로파일링 Confirm 대기 설정 30초이며 실제 대기는 PR 3-2에서 구현한다. Outbox Confirm 대기는 기존 발행 코드의 정책을 유지한다.
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
- Handshake·Channel RPC·Shutdown·채널 확보·연결 종료 대기는 각 3초, NIO 쓰기 큐는 64프레임·삽입 대기 3초다. 공유 채널 캐시는 Boot 기본 25개이며 `spring.rabbitmq.cache.channel.size`로 조정할 수 있다. 프로파일링 워커는 후속 PR 3-2에서 인스턴스당 1개로 구현한다. 운영 적정 용량은 별도 부하 측정 대상이다.
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
