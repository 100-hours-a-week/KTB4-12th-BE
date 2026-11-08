# 공통 RabbitMQ 이미지와 리포트 토폴로지

관련: [BE #546](https://github.com/100-hours-a-week/KTB4-12th-BE/issues/546).

RabbitMQ 4.4.0-management 기반 이미지가 시작할 때 `gift` vhost와 리포트 토폴로지를 가져온다.
Python·curl·별도 시작 스크립트는 사용하지 않는다. 기본 이미지의 entrypoint와 CMD를 그대로 사용한다.

## 파일 역할

- `Dockerfile`: 비밀정보가 없는 설정과 리포트 정의만 이미지에 복사한다.
- `rabbitmq.conf`: `/etc/rabbitmq/definitions` 디렉터리를 부팅 시 import한다.
- `report-definitions.json`: `gift` vhost, Topic Exchange 3개, Classic 큐 4개, binding 7개, policy 3개.
- `access-definitions.example.json`: 계정·권한 Secret의 형식 예시다. 실제로 적용할 비밀번호 해시는 없다.
- `.dockerignore`: 이미지 빌드 컨텍스트를 Dockerfile·설정·리포트 정의로 제한한다.

리포트 큐는 `Report.discord`, `Report.sheets`, `Report.retry`, `Report.dlq`다.
최초 `Report.created`는 두 처리 큐로 분배한다. 재시도는 채널별 routing key를 보존하여
공용 대기 큐에서 30초 후 `report.events`로 돌아간다. DLQ 보관 기간은 7일이다.
TTL/DLX/overflow는 policy이며 처리 큐의 크기 상한은 아직 설정하지 않았다.
프로파일링의 Direct/Quorum 선언은 기존 전용 RabbitAdmin이 담당한다.

이미지는 `rabbitmq_prometheus`도 활성화한다. 메트릭 포트 15692는 로컬 Compose에서 loopback으로만 공개한다.
수집·로컬 경보는 [프로파일링 관측 문서](../../docs/ai-profiling-observability.md)를 따른다.

## 최초 실행

1. 예시를 저장소 밖의 비공개 위치로 복사하고, `password_hash`를 실제 RabbitMQ salted SHA-256 해시로
   바꾼다. 계정 이름은 배포 환경에 맞게 설정하되 `permissions.user`도 일치시킨다.
   RabbitMQ의 `rabbitmqctl hash_password` 또는 공식 관리 도구로 해시를 생성한다.
   실제 비밀번호와 해시는 커밋·이미지 빌드 인자로 전달하지 않는다.
2. `RABBITMQ_ACCESS_DEFINITIONS_FILE`에 완성된 Secret JSON의 절대 경로를 설정한다.
   Compose가 이를 `/etc/rabbitmq/definitions/10-access.json`에 읽기 전용으로 마운트한다.
   컨테이너의 RabbitMQ 사용자가 파일을 읽을 수 있어야 한다. 예시의 administrator 계정은
   개발 초기화용이며 운영에서는 서비스 권한과 관리 권한을 분리하여 준비한다.
3. 다음 명령을 실행한다.

```bash
docker compose -f compose.rabbitmq.yaml up -d --build --wait
docker compose -f compose.rabbitmq.yaml exec rabbitmq rabbitmqctl list_queues -p gift name type durable
docker compose -f compose.rabbitmq.yaml exec rabbitmq rabbitmqctl list_bindings -p gift
docker compose -f compose.rabbitmq.yaml exec rabbitmq rabbitmqctl list_users
```

부팅 import를 사용하면 빈 노드의 기본 사용자 자동 생성에 의존할 수 없다.
기존 Compose의 `RABBITMQ_USERNAME/PASSWORD/VHOST` 대신 Secret 정의를 사용한다.
JSON은 환경변수를 치환하지 않는다. 토폴로지 vhost는 `gift`로 고정되어 있다.
BE의 `SPRING_RABBITMQ_VIRTUAL_HOST=gift`와 계정·비밀번호를 Secret 정의에 맞춘다.
Management API는 공통 `RABBITMQ_MANAGEMENT_URL/USERNAME/PASSWORD`로 설정한다.
BE의 AMQP/Management 계정 설정은 브로커에 사용자를 생성하지 않는다.

## 기존 환경 전환과 업데이트

- 기존 `profiling` vhost의 큐·메시지를 `gift`로 자동 이동하지 않는다. 처리 중 작업을 drain하거나
  별도 이관 계획을 적용한 뒤 producer/consumer의 vhost를 함께 전환한다. 기존 vhost·볼륨은 삭제하지 않는다.
- 기존 `profiling-rabbitmq` 호스트 이름·노드 이름과 데이터 볼륨 이름은 유지한다.
  이 이름을 바꾸면 기존 데이터 경로 인식에 영향을 줄 수 있다.
- 정의 변경은 이미지 재빌드·컨테이너 재생성 후 적용한다. Secret 계정 변경도 재기동 시 import된다.
  같은 이름의 사용자·policy를 재정의하면 기존 값에 영향을 줄 수 있으므로 Secret 내용을 검토한다.
- import는 누락된 기존 리소스를 자동 삭제하지 않는다. 큐 타입 같은 불변 속성 충돌도 자동 마이그레이션하지 않는다.
  앱의 `ReportTopologyPreflight`가 실제 큐·binding·policy를 DB 선점 전에 확인한다.
- Compose healthcheck의 ping은 토폴로지·권한 검증이 아니다. 앱 preflight 통과를 별도로 확인한다.
- 개발 CI는 외부 SSM reconcile 문서를 호출한다. 이 이미지 빌드·배포와 Secret 마운트를 해당 인프라에도
  연결해야 실제 개발 서버에 반영된다. 이 저장소 변경만으로 원격 배포가 완료되는 것은 아니다.

리포트 API는 JSON 접수 계약을 사용한다. Discord·Sheets Consumer는 각각 기본 OFF이며, 토폴로지·두 채널 전달·복구 준비 전에는 `REPORT_MQ_ENABLED=false`를 유지한다. 활성화 조건과 실패 계약은 [리포트 Consumer 구현·검증 기록](https://app.notion.com/p/412ce59041ef81b39559d8079c7a4d0f)를 따른다.
`OUTBOX_ENABLED`와 `AI_PROFILE_AMQP_ENABLED`도 기본 false이며 기존 알림 DB Worker 경로는 그대로다.

## 검증

`ReportMqBrokerIntegrationTest`는 실제 Dockerfile을 빌드하고 테스트 전용 계정 정의를 런타임에 주입한다.
빈 브로커의 자동 import, 인증, 두 큐 분배, 30초 복귀, DLQ, binding 누락, 부분 reject·return을 검증한다.
다른 도메인의 Direct/Quorum 리소스를 선언한 뒤 재import해 보존 여부도 확인한다.

```bash
./gradlew :main-backend:test --tests '*ReportMqBrokerIntegrationTest'
```

공식 근거: [부팅 시 정의 import](https://www.rabbitmq.com/docs/definitions),
[초기 사용자·권한](https://www.rabbitmq.com/docs/access-control).
