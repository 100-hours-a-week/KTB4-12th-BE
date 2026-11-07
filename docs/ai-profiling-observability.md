# 프로파일링 발행 관측·로컬 경보 — PR 4-3

관련 이슈: #526 / 상위 BE 작업 #522. 기존 프로파일·작업 테이블과 Micrometer를 재사용한다.
스키마·AI Consumer·결과 수신 계약은 변경하지 않는다.

## 관측 경로

`Publisher / 작업 DB → MeterRegistry → 인증된 /actuator/prometheus → Prometheus → Alertmanager → 로컬 수신기`

- 로그: 요청 UUID와 고정 오류 코드로 개별 실패·DB 반영 제외·연결 차단/복구를 찾는다.
- 지표: 전체 횟수·시간·크기·현재 상태를 집계한다. requestId·사용자 ID·선점 토큰·본문을 태그로 넣지 않는다.
- ACK_NO_RETURN은 브로커 확인이다. DB 기록 오류와 만료된 선점의 반영 거절은 별도로 센다.
- Counter는 재시작하면 초기화되므로 누적값 대신 `increase`/`rate`로 본다. 고정 오류 코드 Counter는 시작 시 0으로 등록해 첫 실패를 수집할 수 있게 한다.
- DB는 인스턴스마다 60초 간격으로 읽기 전용 집계한다. 조회 실패 시 이전 값을 보존하고 수집 실패·표본 나이를 표시한다. 최초 조회 전 건수는 NaN이다.

| Prometheus 이름 | 의미 |
| --- | --- |
| `profiling_publish_outcomes_total{code}` | 발행 결과, 재시도도 별도 시도로 집계 |
| `profiling_publish_confirm_wait_seconds_count/sum/bucket` | send 반환 후 Confirm 대기 시작부터 성공·타임아웃·예외까지. 연결·전송·AI 분석 시간 제외 |
| `profiling_publish_body_bytes_count/sum/bucket` | UTF-8 요청 본문 크기, 재시도·크기 초과 입력 포함 |
| `profiling_publish_stale_updates_total` | 선점·상태 검증으로 발행 결과 DB 반영 거절 |
| `profiling_publish_db_record_errors_total` | 발행 결과 DB 기록 예외 |
| `profiling_publish_jobs{publish_status}` | WAITING 작업 중 READY·RETRY_WAIT·CLAIMED·BLOCKED 건수 |
| `profiling_publish_oldest_age_seconds` | 미발행 활성 작업의 생성 후 경과 시간. BLOCKED 제외. AI 지연·브로커 대기 시간이 아님 |
| `profiling_publish_db_sample_ok / _age_seconds` | 마지막 DB 조회 성공 여부 / 마지막 성공 후 경과 시간 |
| `profiling_publish_connection_blocked` | 브로커가 통지한 발행 연결 차단. 전체 연결 health와는 다름 |
| `rabbitmq_detailed_queue_messages_ready / _unacked / _consumers` | 큐별 Ready·미ACK·Consumer 수 |
| `rabbitmq_process_resident_memory_bytes / rabbitmq_disk_space_available_bytes` | 브로커 메모리·남은 디스크 |

DB를 공유하는 BE 인스턴스의 작업 수·최장 나이는 `max`로 본다. 발행 Counter 증가량은 합산한다.
여러 환경을 하나의 Prometheus에 연결하면 환경 라벨을 추가하고 경보 집계/그룹도 환경별로 나눠야 한다.
Quorum 복제본 수만큼 Ready를 단순 합산하지 않는다. 현재 설정은 선택한 vhost의 프로파일링 큐만 상세 수집한다.

## 실행

기존 모니터링의 DB exporter 계정·비밀번호 설정은 [모니터링 문서](monitoring.md)를 따른다.
아래 값을 기존 `.env`에 적용하고 앱에 `monitoring` 프로필을 추가한다. 실제 `.env`는 자동 수정하지 않는다.

```dotenv
SCHEDULING_ENABLED=true
AI_PROFILE_SCHEDULING_ENABLED=true
AI_PROFILE_AMQP_ENABLED=true
PROFILING_MONITORING_ENABLED=true
RABBITMQ_METRICS_PORT=15692
RABBITMQ_METRICS_TARGET=host.docker.internal:15692
LOCAL_ALERT_WEBHOOK_URL=http://host.docker.internal:19093/alerts
```

위 주소는 Docker Desktop에서 호스트 JVM·loopback 포트 연결 예시다.
앱·RabbitMQ가 같은 Docker 네트워크에 있으면 실제 네트워크 별칭과 내부 포트를 지정한다.
RabbitMQ Compose는 management/prometheus 플러그인 파일을 마운트한다. 기존 데이터 볼륨은 유지하며,
포트·마운트 반영에는 컨테이너 재생성이 필요하므로 로컬 진행 작업을 확인한 뒤 실행한다. 볼륨 삭제는 필요 없다.
15692는 호스트 loopback으로만 공개한다. 운영은 내부망·인증/TLS 정책을 별도로 정한다.

```bash
# 수신기는 별도 터미널에서 실행한다. 최근 100개 알림의 이름·상태만 메모리에 보관한다.
python3 monitoring/local_alert_receiver.py
```

```bash
# 기존 프로젝트 절차대로 .env를 내보낸 터미널에서 실행한다. 값을 출력하지 않는다.
PYTHONDONTWRITEBYTECODE=1 python3 monitoring/configure.py
docker compose -f compose.monitoring.yaml --profile profiling up -d
docker compose -f compose.monitoring.yaml exec -T prometheus promtool check config /etc/prometheus/prometheus.yml
curl --fail http://127.0.0.1:19093/alerts
```

Linux의 host-gateway는 loopback 접근을 보장하지 않는다. 위 로컬 수신 예시를 운영 주소로 간주하지 않는다.
재현 가능한 격리 통합 테스트는 아래 명령을 사용한다. 운영용 외부 알림 채널은 연결하지 않았다.

## 경보와 운영 순서

규칙: `monitoring/profiling-alerts.yml`. 시간·건수 기준은 초기값이며 운영 부하로 검증한 최적값이 아니다.

| 경보 조건 | 지속 시간 / 대응 |
| --- | --- |
| BLOCKED 1건 이상 | 1분 / 고정 오류 코드 확인 후 PR4-1 수동 복구 절차 |
| 미발행 활성 작업 생성 후 300초 초과 | 2분 / 스케줄러·재시도·발행 연결 확인 |
| DB 표본 실패 또는 180초 초과, 지표 자체 누락 | 2분 / 수집·DB·기능 플래그 확인. 0건으로 해석하지 않음 |
| 최근 5분 발행 실패 증가 | 즉시 평가 / Return·NACK·timeout·인증·설정 오류 구분 |
| 최근 5분 DB 기록 실패 증가 | 즉시 평가 / 브로커 전달 여부가 불명확할 수 있으므로 임의 새 요청 생성 금지 |
| 발행 연결 blocked | 1분 / 브로커 자원 알람 확인 |
| 요청 Ready 550건 초과 | 2분 / AI Consumer 수·처리량·증가 추세 확인 |
| 메모리 / configured watermark > 90%, 디스크 < configured low watermark | 1분 / 브로커 자원 확인 |
| spring/rabbitmq/rabbitmq-queues 수집 대상 DOWN | 1분 / 수집 경로 확인. AI 장애라고 단정하지 않음 |

Ready 550건 ≈ 5분은 정상 처리량 110건/분을 가정한 추정이다. 모델 장애·429 상황에는 적용하지 않는다.
Alertmanager는 10초 group_wait/group_interval, 4시간 repeat_interval, send_resolved=true를 사용한다.
조건 해제 후에도 수집·평가·그룹 간격에 따른 전달 지연이 있다. 실패 증가량 경보는 5분 조회 창 동안 남을 수 있다.
로컬 수신기는 외부 통지를 보내지 않으며 재시작하면 수신 이력이 사라진다.

대시보드가 필요하면 기존 Grafana의 Prometheus 데이터 소스에서 위 지표를 조회한다. 새 대시보드·외부 채널·결과 미수신 경보는 추가하지 않았다.

## 검증

```bash
./gradlew :main-backend:test --tests '*ProfilingObservabilityIntegrationTest'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s monitoring -p 'test_*.py'
docker run --rm --entrypoint promtool -v "$PWD/monitoring:/rules:ro" prom/prometheus:v3.15.0 test rules /rules/profiling-alerts.test.yml
```

통합 테스트는 임시 MySQL·RabbitMQ 4.4·Prometheus·Alertmanager와 로컬 HTTP 수신기를 사용한다.
자동 스케줄러를 끄고 실제 Publisher·DB·관리 포트·운영 경보 규칙을 직접 연결한다. 기존 로컬 서비스는 변경하지 않는다.

- 실제 수집 대상 3개 UP, ACK·Confirm·UTF-8 크기·Ready·브로커 메모리/디스크 수집.
- 테스트 브로커의 Binding 제거 → Return → 작업 BLOCKED → 실제 경보 발생·로컬 수신.
- 테스트 행을 SUPERSEDED로 바꿔 조건 제거 → 실제 해제 알림 수신. PR4-1의 수동 재개 자체를 시험한 것은 아니다.
- 관리 포트는 기존 모니터링 Basic 인증을 사용한다. 테스트 종료 시 임시 서비스·행·파일을 정리한다.
- 실행 결과는 Git 제외 경로 `main-backend/build/reports/profiling-observability.txt`에 기록한다.

2026-11-07 첫 격리 검증 통과: 본문 44바이트, 두 발행의 Confirm 대기 합계 약 0.00658초.
작은 계측 검증 표본이며 실제 서비스 p95·처리량·최대 본문 크기나 용량 산정 근거가 아니다.
최종 전체 회귀 결과는 PR에 기록한다. 실제 AI/429·OOM·자원 고갈·다중 노드 장애·운영망·외부 알림은 시험하지 않았다.

공식 근거: [RabbitMQ Prometheus](https://www.rabbitmq.com/docs/prometheus),
[Prometheus 경보 규칙](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/),
[Alertmanager 설정](https://prometheus.io/docs/alerting/latest/configuration/).
