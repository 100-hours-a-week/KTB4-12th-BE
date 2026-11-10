# 프로파일링 결과 큐 Listener — PR3

관련 작업: [BE #583](https://github.com/100-hours-a-week/KTB4-12th-BE/issues/583),
[상위 #522](https://github.com/100-hours-a-week/KTB4-12th-BE/issues/522).
선행: [PR1 결과 계약](ai-profiling-result-contract.md), [PR2 원자적 저장](ai-profiling-result-store.md).

## 범위와 활성화

PR2 저장 서비스를 실제 `profiling.result` 큐에 연결한다. 새 DB 컬럼이나 HTTP 계약 변경은 없다.
`AI_PROFILE_RESULT_CONSUMER_ENABLED=false`가 기본이며, 활성화 시 `AI_PROFILE_AMQP_ENABLED=true`와
공통 AMQP/Management 접속 설정 및 브로커 구성이 필요하다.
요청 발행 스케줄러 활성화는 결과 수신의 전제 조건이 아니다.
기존 AMQP 기능을 켜면 네 큐의 선언은 수행한다. 결과 Consumer 플래그는 구독을 제어하며,
배포 계정에는 추가된 두 실패 큐의 선언·Binding 권한도 준비해야 한다.
기능 OFF에서는 결과 Consumer와 그 전용 Management 설정 의존성을 추가하지 않는다.
리포트 Consumer 등 다른 기능이 Management 설정을 요구하는 조건은 유지한다.

이 PR은 오류 시 최소 안전 중지까지만 제공한다. PR4의 자동 복구·운영 제어 전에는
테스트에서만 구독을 켜고 운영 설정은 OFF로 유지한다.

## 추가·수정 클래스와 파일

| 클래스·파일 | 책임·변경 | 이유 |
| --- | --- | --- |
| ProfilingResultConsumerConfiguration | 공통 rabbitConnectionFactory·RabbitAdmin·Management 클라이언트로 결과 전용 컨테이너 구성 | 접속 설정 중복 없이 수신 기능을 독립적으로 켜고 끔 |
| ProfilingResultListener | 선언 → 사전 검증 → 구독, 오류 시 중지 게이트와 비동기 컨테이너 중지 | Listener가 자기 종료를 기다리는 교착과 오류 후 반복 소비 방지 |
| ProfilingResultMessageListener | 본문/AMQP 식별자 검증 → PR2 호출 → 개별 ACK 또는 requeue=false 거절 | DB 커밋보다 ACK가 먼저 나가 메시지를 잃지 않도록 함 |
| ProfilingResultTopologyPreflight | 실제 Exchange·Quorum·유효 Policy·Binding·Feature Flag·AMQP 계정 권한 확인 | 설정 파일 존재만으로 안전한 DLX 전달을 가정하지 않음 |
| ProfilingAmqpConfiguration | 기존 요청/결과 큐 외 요청/결과 실패 Quorum 큐와 Binding 선언 | 원본 요청 실패와 BE 결과 소비 실패를 구분하여 조사·복구 |
| ProfilingRecoveryCommand / OutboxReplayCommand | 일회성 운영 명령의 시작 인자에서 결과 Consumer도 명시적으로 OFF | 서버 환경의 수신 ON 설정을 상속해 복구 명령 기동이 실패하거나 메시지를 소비하는 일 방지 |
| application.yaml / .env.example | 결과 Consumer 기본 OFF 플래그 추가 | 요청 발행 설정과 분리하여 단계적으로 검증 |
| profiling-definitions.json | 결과 at-least-once DLX, 실패 큐 reject-publish Policy | DLQ 수락 확인 전까지 브로커가 원본을 보존 |
| Dockerfile / .dockerignore | Policy JSON을 실제 이미지에 포함하고 시작 시 import | Java 소스 경로의 파일이나 제외된 빌드 파일은 배포에 반영되지 않음 |

### 핵심 메서드

- `ProfilingResultConsumerConfiguration.profilingResultListener()`: 공통 연결과 PR1 Reader·PR2 서비스를 주입받아 결과 전용 컨테이너와 중지 경로를 연결한다.
- `ProfilingResultListener.start()`: Admin 선언, 실제 구성 검증, 구독 시작 순서로 실행한다. 실패 시 중지 상태를 기록하고 시작 오류를 전파한다.
- `ProfilingResultListener.halt(reason)`: 최초 오류에서만 중지 게이트를 닫고 비동기 종료를 요청한다. 추가 메시지는 저장하거나 ACK하지 않는다.
- `ProfilingResultListener.onApplicationEvent()`: 자신이 소유한 컨테이너의 소비 실패만 중지 처리한다. 다른 도메인 이벤트는 무시한다.
- `ProfilingResultMessageListener.onMessage()`: 수신 메시지의 본문·식별자를 검증하고 PR2의 저장 결론에 따라 ACK·거절·중지를 선택한다.
- `ProfilingResultMessageListener.settle()`: `multiple=false` ACK 또는 `requeue=false` 거절을 보낸다. 통신 오류에는 추가 정산을 시도하지 않고 중지한다.
- `ProfilingResultTopologyPreflight.verify()`: 유효 구성과 실제 AMQP 계정 권한을 검증한다. 안전 조건이 하나라도 빠지면 예외로 시작을 차단한다.
- `ProfilingAmqpConfiguration.profilingTopology()`: 공통 Exchange에 네 Quorum 큐와 이름별 Binding을 해당 전용 Admin 소유로 선언한다.

## 수신·정산 순서

1. 메시지 본문은 PR1 Reader로 검증한다. `message_id`·`correlation_id`가 본문 `requestId`와 모두 같아야 한다.
2. PR2 `ProfilingResultService.save()`를 Spring 프록시로 호출한다. 정상 반환 시 독립 DB 트랜잭션의 커밋이 완료된다.
3. `RECORDED`·`DUPLICATE`·`IGNORED`는 `basicAck(deliveryTag, false)`로 해당 메시지만 ACK한다.
4. 본문/식별자 오류 또는 `QUARANTINE`은 `basicReject(deliveryTag, false)`로 거절한다. 브로커가 결과 DLQ로 전달한다.
5. DB·미분류·ACK/거절 통신 오류는 성공이나 입력 오류로 바꾸지 않고 Consumer를 중지한다. 미ACK는 채널 종료 후 재배달 대상이다.

정상적인 `outcome=FAILED` 결과는 업무 실패를 DB에 기록하고 ACK한다. 결과 DLQ 대상이 아니다.
ACK가 실패해 이미 커밋한 메시지가 다시 와도 PR2의 DB 멱등 검증으로 재반영하지 않는다.
중지 후 같은 Listener 인스턴스의 재시작은 차단한다. 재기동/새 인스턴스의 복구 검증은 테스트로만 수행하며 자동 재개는 PR4 범위다.

## 컨테이너 설정 근거

- 소비 동시성 1·최대 동시성 1·prefetch 1·batch 1·수동 ACK. 자동 확장과 배치 ACK는 사용하지 않는다.
- AI 분석 동시성 8과 BE DB 반영의 적정 동시성은 별개다. 우선 미ACK·DB 경합을 작게 제한하고 처리 시간·적체를 측정한 뒤 조정한다.
- 여러 BE 인스턴스가 켜지면 Consumer 수와 미ACK 상한도 합산된다. 동시성 1만으로 중복 처리가 막히지 않으므로 DB 잠금·fingerprint 검증을 유지한다.
- stop(callback)·forceStop·forceCloseChannel·종료 대기 30초를 사용한다. 오류 게이트는 즉시 닫고 실제 컨테이너 종료는 비동기로 요청한다.
- 클라이언트 자동 복구를 금지하고 컨테이너 복구 재시도도 비활성화한다. 미분류 예외가 기본 오류 처리로 영구 격리되지 않도록 defaultRequeueRejected=true를 명시하되, 정상 오류 분기는 직접 중지해 즉시 재큐잉 루프를 피한다.

## 브로커와 DLX

`gift` vhost, durable Direct Exchange `profiling.exchange`, durable Quorum 큐 4개를 사용한다.
각 큐 이름과 같은 Routing Key로 Binding한다.
결과 큐 Policy는 `dead-letter-exchange=profiling.exchange`,
`dead-letter-routing-key=profiling.result.failed`, `dead-letter-strategy=at-least-once`,
`overflow=reject-publish`다. 두 실패 큐에도 reject-publish를 적용하고 TTL·큐 자동 만료는 설정하지 않는다.
AI 요청 큐의 DLX·ACK 정책은 변경하지 않는다.

BE가 실패 큐에 직접 재발행하면 Confirm·Return 확인과 원본 ACK 사이의 실패/중복을 별도로 다뤄야 한다.
기존 결과 큐가 Quorum이므로 이 PR은 브로커가 DLQ 수락 확인과 재전달을 맡도록 한다.
대신 Policy·권한·Binding을 정확히 유지해야 하며, DLQ 장애 때 보존된 메시지로 원본 큐 자원이 늘 수 있다.
자세한 실패 이유는 BE 로그로 추적하며 본문·민감한 입력·내부 예외 전문은 출력하지 않는다.
DLQ 복구가 곧 즉시 재전달을 뜻하지는 않는다. RabbitMQ 4.4 기본 DLX Confirm/재전달 타이머는
180초이므로 장애 시험은 브로커 값을 바꾸지 않고 최대 210초 동안 최종 전달을 기다린다.
일반 ready 건수가 0이라는 사실만으로 DLQ 전달 완료를 판단하지 않는다. 내부 DLX 전달 대기도 운영 관측에 포함해야 한다.

Preflight는 일반 Policy뿐 아니라 Operator Policy가 병합된 `effective_policy_definition`과 큐 인자를 검증한다.
큐 조회에는 `disable_stats=true`를 사용한다. RabbitMQ 4.4에서 신규 큐의 통계가 수집되기 전에는
일반 응답에 Policy가 누락될 수 있으므로 통계 캐시 대신 실제 큐 메타데이터로 검증한다.
실제 AMQP 계정의 권한을 확인하며 Management 계정 권한으로 대체하지 않는다.
시작 전 RabbitAdmin이 누락된 큐·Binding을 먼저 복원한 경우에는 복원된 상태를 재검증한 뒤 구독한다.
복원/검증이 실패하거나 Policy·권한·TTL이 잘못되면 구독을 시작하지 않는다.
이 검증은 시작 시점의 상태이며 실행 중 Policy 변경을 지속 감시하는 기능은 아니다.

## 테스트와 검증 범위

| 테스트 | 검증 |
| --- | --- |
| ProfilingResultMessageListenerTest | 결과별 ACK/거절, 잘못된 본문·식별자, DB/미분류/통신 오류 보존 |
| ProfilingResultListenerTest | 선언·검증·시작 순서, 비동기 중지, 중복 중지와 재시작 차단, 다른 컨테이너 이벤트 무시 |
| ProfilingResultConsumerConfigurationTest | OFF 격리, AMQP 필수 조건, 공통 연결·수동 ACK·동시성/prefetch 설정 |
| ProfilingResultTopologyPreflightTest | 잘못된 Policy·큐 인자·TTL·Binding·Feature Flag·권한 거부 |
| ProfilingResultBrokerIntegrationTest | 실제 RabbitMQ 4.4/MySQL에서 커밋 후 ACK·중복·DLX·오류 중지·미ACK 복구·두 Consumer 경합 |
| ProfilingAmqpConfigurationTest | 실패 큐를 포함한 4개 durable Quorum 선언 |
| ReportMqBrokerIntegrationTest | 공통 이미지 변경 후 기존 리포트 토폴로지/전달 회귀 |
| ProfilingRecoveryScriptIntegrationTest / ReplayRequestTest | 서버에서 결과 Consumer를 켠 설정이 있어도 일회성 복구 명령은 구독하지 않고 기존 동작 유지 |

```bash
./gradlew :main-backend:test --tests '*ProfilingResult*Test' --tests '*ProfilingAmqpConfigurationTest' --tests '*ReportMqBrokerIntegrationTest' --offline
./gradlew :main-backend:test :payment-server:test --offline --continue
./gradlew :main-backend:build :payment-server:build -x test --offline
docker build -t gift-rabbitmq-pr3-check infrastructure/rabbitmq
```

운영 브로커·실제 AI 연동·3노드 장애 전환·자동 복구·운영 경보/제어는 이 검증에 포함하지 않는다.
시작 검증은 TTL 미설정을 확인하지만 AI 발행 메시지별 expiration 설정까지 보장하지 않으므로 AI 인계 계약도 유지해야 한다.

근거: [RabbitMQ Quorum dead lettering](https://www.rabbitmq.com/docs/quorum-queues#dead-lettering),
[RabbitMQ 4.4 큐 메타데이터 처리](https://github.com/rabbitmq/rabbitmq-server/blob/v4.4.0/deps/rabbit/src/rabbit_quorum_queue.erl#L2244).
DLX 타이머 기준: [RabbitMQ 4.4 기본값](https://github.com/rabbitmq/rabbitmq-server/blob/v4.4.0/deps/rabbit/Makefile#L114).

## 2026-11-09 최종 검증 결과

- PR3 신규 테스트 69건: 단위 54건·실제 RabbitMQ/MySQL 통합 15건 전부 통과.
- BE 전체: 총 2,136건, 성공 2,127건, 실패/오류 0건, 기존 환경 조건부 제외 9건.
- 제외 항목은 Elasticsearch 조회·동기화 통합 테스트이며 이번 변경으로 추가한 제외는 없다.
- 결제 모듈: cleanTest 후 실제 재실행, 52건 전부 통과.
- 최종 전체 회귀 명령은 12분 27초에 성공했다. 앞선 실행은 복구 명령 보완을 위해 중단했으며 최종 성공 집계에 섞지 않았다.
- 두 모듈 build(-x test), 최종 import 정리 후 테스트 소스 컴파일, 실제 Docker 빌드, git diff --check 통과.
- 복구 명령 두 곳의 새 Consumer 플래그 충돌은 회귀 테스트로 먼저 재현한 뒤 수정하여 관련 7건 통과를 확인했다.
- 운영 환경·실제 AI 연결은 실행하지 않았고 커밋·푸시·PR 생성은 수행하지 않았다.

## PR4 검증 기록

2026-11-10 [PR4 실제 장애·전체 회귀 검증](ai-profiling-result-pr4-validation.md)에서 실제 DB 접속 차단·RabbitMQ 중단/복구, 발견한 오류 분류 결함 수정 및 최종 전체 회귀 결과를 확인할 수 있다.

[PR4 장애 복구·운영 제어 구현](ai-profiling-result-recovery.md)에서 추가 클래스·상태 전이·설정·운영 제어·테스트의 역할과 미검증 범위를 확인할 수 있다.

PR4에서는 핵심 동작 검증을 위해 테스트 전용 DLQ Confirm 대기를 5초로 단축하고 실제 DB/브로커 통합의 복구 시점을 명시적으로 제어한다. PR3의 기본 180초 주기 검증 기록은 역사적 결과이며 현재 반복 시험의 대기 조건과 구분한다.
