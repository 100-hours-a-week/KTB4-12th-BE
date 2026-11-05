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

BE 실행 환경에 `AI_PROFILE_AMQP_ENABLED=true`와 접속 정보를 전달하면 RabbitAdmin이 선언한다. 비활성 기본값에서는 관련 Bean과 연결을 생성하지 않는다.

- RabbitMQ 4.4.0-management, 고정 노드 이름·데이터 볼륨
- Direct Exchange `profiling.exchange`
- Quorum 큐·Routing Key `profiling.request`, `profiling.result`
- Durable·exclusive=false·auto-delete=false, Persistent JSON/UTF-8
- 기본 연결은 RabbitAdmin, RabbitTemplate은 별도 발행 연결 사용
- 연결 3초·Heartbeat 요청 20초·Confirm 대기 설정 30초. 실제 Confirm 대기는 PR 3-2에서 구현
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
