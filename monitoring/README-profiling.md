# 프로파일링 요청·결과 큐 모니터링 전달 안내

## 클라우드 전달 파일

| 파일 | 적용 위치 |
| --- | --- |
| `grafana/dashboards/gift-profiling.json` | 기존 Grafana 대시보드 import 또는 file provisioning |
| `grafana/provisioning/dashboards/dashboards.yaml` | file provisioning 사용 시 기존 provider 재사용 |
| `profiling-scrape.example.yml` | 기존 Prometheus 설정에 scrape_configs/rule_files 병합 |
| `profiling-alerts.yml` | Prometheus rule_files 경로에 마운트 |
| `profiling-alerts.test.yml` | promtool 경보 조건 시험 |

예시의 주소·environment·cluster·vhost를 실제 값으로 교체한다. 기존 Prometheus 설정 전체를 덮어쓰지 않는다.
Prometheus Operator 환경에서는 같은 내용을 ServiceMonitor/PodMonitor와 PrometheusRule로 반영한다.
Grafana JSON은 기존 datasource UID `gift-prometheus`를 사용한다. 기존 운영 데이터 소스 UID로 모든 panel/variable의 datasource UID를 맞추거나 같은 UID의 데이터 소스를 연결한다.
로컬 Compose는 기존 provider와 볼륨 마운트가 JSON 폴더 전체를 읽으므로 provider YAML 추가 없이 자동 등록한다.

## 수집·화면

- `rabbitmq_prometheus` plugin의 기본 `/metrics`와 상세 `/metrics/detailed`를 **각 노드별로** 수집한다. AMQP 5672/5671·관리 UI 15672와 다른 metrics 포트이며 예시는 15692이다.
- 요청 `profiling.request`, 결과 `profiling.result`, DLQ `profiling.request.failed`·`profiling.result.failed` 네 큐를 수집한다.
- 상세 family는 `queue_coarse_metrics`·`queue_consumer_count`만 요청한다. 큐 정규식 지원은 운영 RabbitMQ 버전에서 확인한다.
- 접근 경로는 내부망으로 제한하며, TLS/인증/프록시는 실제 운영 기준에 맞춰 클라우드에서 적용한다. 비밀값은 전달 파일에 넣지 않는다.
- JSON은 environment/cluster/vhost 조회 변수를 제공한다. 로컬처럼 라벨이 없으면 All을 사용한다. 운영에서는 environment/cluster 라벨을 지정한다.
- 요청·결과 각각 ready/unacked/consumer 수, DLQ ready, 노드별 수집 상태를 표시한다. 요청 Consumer는 AI, 결과 Consumer는 BE이다.
- 동일 논리 큐의 노드별 표본을 합산하지 않고 environment/cluster/vhost/queue별 max로 조회한다. 이 화면은 복제본 수·쿼럼 가용성 검증을 대신하지 않는다.
- No data는 0으로 채우지 않는다. 각 노드의 up과 함께 보며, up=1도 모든 큐의 존재·표본 최신성·업무 정상 처리를 보장하지 않는다.

## 경보

기존 11개 규칙을 유지하고 요청 적체를 environment/cluster/vhost/queue별로 분리했다.
추가 규칙은 결과 ready > 550이 2분 지속, 요청/결과 ready > 0이면서 consumer=0이 2분 지속, DLQ ready > 0이 1분 지속이다.
550건은 기존 요청 적체 기준을 공유한 **초기 조사 기준**이며 결과 처리 성능이나 운영 SLO로 검증된 값이 아니다. 운영 활성화 전에 AI/BE 처리량과 허용 지연에 맞춰 확정한다.
Consumer 부재 규칙은 기능 OFF·수동 중지를 자동 구분하지 않는다. 대기 메시지를 보존하는 계획 중지에서는 해당 environment/cluster/vhost/queue로 silence한다. 빈 큐와 DLQ에는 Consumer 부재 경보를 적용하지 않는다.
경보는 조사용이며 자동 재개·요청 재발행·FAILED 전환·DLQ 재처리를 하지 않는다.
기존 발행 규칙에는 환경을 합치는 집계가 남아 있으므로, 여러 환경이 공유하는 Prometheus에 적용할 때 클라우드 담당자는 해당 규칙도 환경별로 분리한다.
Alertmanager 수신처·grouping(environment/cluster/vhost/queue/alertname)·반복 주기는 운영 설정에서 연결한다. 로컬 configure.py의 수신기는 검증용이며 운영 수신처가 아니다.

## 적용 확인

1. 노드별 targets UP와 네 큐의 실제 노출 확인. 지표가 없으면 0건으로 판단하지 않는다.
2. Grafana의 `Gift / Profiling Queues`에서 environment/cluster/vhost를 선택하고 요청·결과·DLQ 확인.
3. `promtool check config` 및 `promtool check rules`, 아래 규칙 시험 실행.
4. 격리 환경에서 메시지 대기·Consumer 부재·해제를 만들고 패널/경보/알림 해제를 확인한다. 운영 큐는 시험 목적으로 중지하지 않는다.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s monitoring -p 'test_*.py'
docker run --rm --entrypoint promtool -v "$PWD/monitoring:/rules:ro" prom/prometheus:v3.15.0 test rules /rules/profiling-alerts.test.yml
```

공식 수집 계약: https://www.rabbitmq.com/docs/prometheus#detailed-endpoint
