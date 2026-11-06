# 공통 Outbox 실행·완료 계약

관련 작업: [BE #545](https://github.com/100-hours-a-week/KTB4-12th-BE/issues/545),
[Wiki #234](https://github.com/100-hours-a-week/KTB4-12th-wiki/issues/234).

## 실행 경로

| 이벤트 | 담당 | 완료 상태 | 구현 범위 |
| --- | --- | --- | --- |
| `notification.create.v1` | 알림 DB Worker | `PROCESSED` | 상태 계약은 #545, 실제 Worker·생산자 전환은 #550 |
| `product.search.sync.v1` | 기존 MQ Relay | `PUBLISHED` | 기존 발행 경로 유지 |
| `report.created.v1` | 리포트 MQ Relay | `PUBLISHED` | 실제 발행 연결은 #546 |

MQ claim은 알림 타입을 거부하며 알림의 만료 임대도 회수하지 않는다. 완료·실패 갱신에서도 알림을 제외한다.
기존 알림 writer/worker는 아직 `notification_outbox`를 사용한다. 새 DB Worker를 이 변경에서 활성화하지 않는다.

## 상태·트랜잭션

- `PUBLISHING`일 때만 `claim_token`과 `lease_until`이 모두 존재한다.
- `PUBLISHED`일 때만 `published_at`이 존재하고 `processed_at`은 NULL이다.
- `PROCESSED`일 때만 `processed_at`이 존재하고 `published_at`과 claim 정보는 NULL이다.
- `PENDING`/`FAILED`는 완료 시각과 claim 정보를 갖지 않는다.
- V48은 변경하지 않고 V50으로 컬럼·CHECK·정리용 인덱스를 확장한다. 기존 유효 행은 그대로 유지한다.
- MQ `complete()`는 브로커 확인을 독립 트랜잭션으로 저장한다. 알림에 이 메서드를 사용하지 않는다.
  #550의 알림 Worker는 짧은 DB 트랜잭션에서 행을 잠그고 알림 저장과 `PENDING → PROCESSED`를 함께 커밋해야 한다.
  SSE/Redis 신호는 커밋 뒤에 보내며 DB 완료를 되돌리지 않는다.

## 원본과 전달 메타데이터

- `eventId`는 최초 Writer INSERT에서 생성한다. 재발행·재시도·DLQ replay에서도 같은 ID를 사용한다.
- `eventType`, `schemaVersion`, `deduplicationKey`, 업무 대상, `payload`, `occurredAt`, `traceId`는 원본 이벤트다.
  Relay의 상태 갱신·재시도 과정에서 이 원본을 변경하지 않는다. `JsonNode` 자체의 불변성을 보장하는 API라는 뜻은 아니다.
- `claim_token`은 DB 발행 시도마다 바뀌며 `eventId:claimToken`은 confirm 상관관계 식별자다. 업무 멱등 키가 아니다.
- `retryCount`, `nextAttemptAt`, `delivery.channel`, 원본 큐·실패 분류는 후속 리포트 소비 계층의 가변 headers다.
  원본 payload를 다시 쓰거나 MQ 발행 실패 횟수인 `failure_count`와 혼용하지 않는다.
  정확한 AMQP header 타입·이름과 초기값은 #548에서 명세·구현하며 #545에 이미 구현됐다고 간주하지 않는다.

## 보존·정리

`OutboxRetentionService`는 다음 두 경우만 완료 시각으로부터 7일이 지난 행을 정리한다.

- `report.created.v1` + `PUBLISHED`: `published_at` 기준
- `notification.create.v1` + `PROCESSED`: `processed_at` 기준

미완료·FAILED, 기존 알림 PUBLISHED/PUBLISHING, 검색 및 다른 타입, `notifications`와 구형 도메인 Outbox는 삭제하지 않는다.
생성한 지 오래됐어도 최근 완료한 행은 보존한다. 다른 도메인의 보존 정책을 이 작업에서 7일로 바꾸지 않는다.

초기 실행 간격은 `OUTBOX_CLEANUP_INTERVAL_MS=3600000`(1시간), 실행당 타입별 최대 1,000행이다.
첫 실행도 해당 간격 후이며, 다음 실행에서 남은 오래된 완료 행을 계속 정리한다. 따라서 7일은 최소 보존 기간이다.
초기값은 부하 측정 결과가 아니다. 삭제 backlog가 쌓이면 관측 후 실행 간격·배치 크기를 조정한다.
`app.scheduling.enabled=false`이면 자동 실행하지 않는다. MQ Relay의 `OUTBOX_ENABLED`와는 독립적이다.

## 배포·호환

1. 확장 마이그레이션 V50을 적용하고 새 애플리케이션을 배포한다. 적용된 V48을 수정하지 않는다.
2. 모든 인스턴스에서 구형 알림 MQ Relay가 중지됐는지 확인한 뒤 #550 생산자/Worker를 전환한다.
3. 공통 테이블에 기존 알림 `PUBLISHING`/`PUBLISHED`가 있다면 업무 처리 여부를 확인한다.
   이 변경은 해당 행을 자동으로 PENDING으로 바꾸거나 삭제하지 않는다. 무조건 재처리하면 기존 MQ 소비와 중복될 수 있다.
4. 기존 `notification_outbox`의 잔여 작업 drain과 실패 해결은 #550에서 수행한다.

알림 MQ용 `OUTBOX_NOTIFICATION_ROUTING_KEY`, `OUTBOX_NOTIFICATION_BATCH_SIZE`는 제거했다.
검색 활성화에는 기존 Exchange와 검색 routing key가 계속 필요하다. 남아 있는 옛 환경변수는 읽지 않는다.
RabbitMQ 토폴로지·버전·큐 종류·검색 정책은 변경하지 않았다. 롤백된 구형 바이너리는 알림을 다시 발행할 수 있으므로
Worker 전환 뒤의 앱 롤백은 자동으로 안전하다고 가정하지 않는다.
