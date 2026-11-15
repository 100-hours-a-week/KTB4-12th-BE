# 프로파일링 MQ 워커·prefetch·큐 상한 산정 실험

백엔드 폴더에서 실행한다. Docker Desktop, uv, k6와 형제 폴더
`KTB4-12th-AI-mq-consumer/profiling`이 필요하다. 해당 AI 프로젝트의 고정 의존성을 재사용한다.

## 실행

```bash
cd gift_BE
bash scripts/profiling-k6/run.sh check
bash scripts/profiling-k6/run.sh up

# 약 10건: 30건/분 × 20초. 분석은 기본 평균 4.1초의 모의 처리.
bash scripts/profiling-k6/run.sh run --workers 1 --prefetch 1 --rate 30 --duration 20

# 워커 비교: prefetch 8 고정, 워커 1 → 2 → 4 → 8. 각 조합 3회.
bash scripts/profiling-k6/run.sh matrix --rate 120 --duration 60 --repeat 3

# 선택한 워커 4에서 prefetch만 비교하는 예
bash scripts/profiling-k6/run.sh run --workers 4 --prefetch 4 --rate 120 --duration 60
bash scripts/profiling-k6/run.sh run --workers 4 --prefetch 8 --rate 120 --duration 60
bash scripts/profiling-k6/run.sh run --workers 4 --prefetch 16 --rate 120 --duration 60

# 시험 환경 중지. 볼륨·결과는 보존한다.
bash scripts/profiling-k6/run.sh down
```

`matrix`는 중간 조합이 실패하면 중단한다. 유입 중단 후 기본 최대 900초까지 완료를 기다린다.
k6의 종료 경계에 따라 1건이 추가될 수 있으며 실제 발행 건수를 보고한다.
zsh에서 주석 줄까지 붙여넣으면 `command not found: #`가 나올 수 있다. 실행 명령 줄만 복사한다.
Management HTTP 발행은 낮은 유입률에서 유휴 연결 재사용의 영향을 줄이도록 요청별 새 연결을 쓴다.
HTTP 오류를 자동 재시도해 숨기지 않는다. `attempted`, `publish_failed`, `publish_errors`를 함께 확인한다.
`completion_ratio`는 발행 확인된 요청 기준, `attempt_success_ratio`는 전체 시도 기준이다.
슬롯 1에서 220건/분을 10분 주입하면 적체가 매우 크므로 단일 실행도 오래 걸리거나 미완료로 끝날 수 있다.
우선 `--rate 30 --duration 20` smoke를 통과시킨 후 짧은 비교(`--rate 120 --duration 60`)부터 실행한다.
포화 처리량과 안정 유입률은 다르다. 적체 중 측정된 처리량과 회복 시간을 함께 읽는다.

## 다음 단계: prefetch 자동 비교

워커 8을 고정하고 prefetch 8·16·32를 기본 3회씩 비교한다. 이전 워커 비교와 같은
분당 120건·60초·모의 분석 평균 목표 4.1초 조건이다. 실행은 사용자가 직접 시작한다.

백엔드 폴더에서 실행한다. 아래 블록은 zsh에 그대로 붙여넣을 수 있도록 주석 줄을 넣지 않았다.

```bash
bash scripts/profiling-k6/run.sh up
bash scripts/profiling-k6/run.sh prefetch --rate 120 --duration 60 --repeat 3
```

짧은 1회 사전 비교만 하려면 다음 명령을 사용한다.

```bash
bash scripts/profiling-k6/run.sh prefetch --rate 120 --duration 60 --repeat 1
```

- 1회차는 P=8→16→32, 2회차는 16→32→8, 3회차는 32→8→16 순서다. seed·입력 시간 분포는 고정한다.
- `--workers`·`--prefetch`는 생략한다. 이 동작은 W=8과 P=8/16/32를 직접 지정한다.
- 각 실행은 새 Python 프로세스다. RSS 최고값이 앞선 실행에서 누적되지 않는다.
- 기본 총 9회다. 부하 주입만 9분이며 결과 회복·기동·종료 시간이 추가된다.
- 발행·정합성 실패나 결과 파일 부재 시 다음 조합을 실행하지 않고, 부분 보고서에 중단 사유를 남긴다.
- 기존 결과를 덮어쓰거나 큐를 자동으로 비우지 않는다. 실행 중 Ctrl+C로 중단한 경우 남은 큐를 먼저 조사한다.

결과는 `results/<UTC시각>-prefetch-<ID>/` 한 폴더로 묶는다.

| 파일 | 내용 |
| --- | --- |
| `suite-manifest.json` | 전체 실행 설정·예정 횟수 |
| `report.md` | 합의한 열의 비교 표, 반복 집계, 정합성, 관측·해석 구분, 다음 변경·미측정 항목 |
| `comparison.csv` | 실행별 비교 표 |
| `comparison.json` | 원본 요약과 중단 여부를 포함한 통합 결과 |
| `r01-w8-p8/` 등 | 각 실행의 입력·이벤트·k6 로그·요약·개별 보고서 |

report.md는 각 실행이 끝날 때 갱신되므로 중간 실패도 숨기지 않는다. 반복 결과는 최솟값·중앙값·최댓값으로
표시하며 p95를 평균 내지 않는다. 이전 일반 `run` 명령도 같은 보고서 열을 사용한다.
모니터링은 기존 Grafana 대시보드를 그대로 사용하고 run_id별로 확인한다.

이번 단계도 워밍업 없는 사전 비교다. 3회 반복만으로 워밍업 2분·10분 측정·실제 AI 검증이 완료되지는 않는다.
설정은 자동 변경하지 않으며, 최적값·운영 안정 유입량 확정은 후속 장시간/실제 AI 검증까지 보류한다.

## 측정 범위

`k6 → Management HTTP publish → 실제 Quorum 요청 큐 → 기존 AI MqRuntime/DB 잠금·결과 저장
→ 실제 결과 큐 → 결과 수집 원장 기록·ACK` 경로다.

분석 함수만 평균 약 4.1초·분포 sigma 0.36·상한 12.8초의 합성 응답으로 교체한다.
난수는 입력 순서와 seed로 결정해 워커 순서에 영향을 받지 않는다. 실제 모델·검색·BE는 호출하지 않는다.
DB 풀은 모든 조합에서 24개로 고정한다. 워커는 1~8, prefetch는 워커 이상 32 이하로 제한한다.
제품 코드와 기존 개발 DB·큐를 변경하지 않는다. DB 마이그레이션은 전용 PostgreSQL에만 적용한다.
실행 중인 명령이 있거나 큐에 메시지/소비자가 남아 있으면 새 실행을 거부하며 purge하지 않는다.

`run`, `prefetch`, `sustained`의 개별 실행은 독립 프로세스이며 기존 `matrix`는 한 프로세스에서 순차 실행한다.
`run`·`prefetch`는 기본 워밍업이 없고 `sustained`는 기본 120초다. `--warmup`으로 명시할 수 있다.
이 결과만으로 실제 v3의 최적 설정·운영 용량을 확정하지 않는다. 전체 경로/BE 배치 비교,
실제 AI, 장애 주입, 입력 크기별 분포는 아직 포함하지 않는다.

## 다음 단계: 부하별 큐 누적량과 상한 산정 근거 수집

현재 목적은 동시 워커·prefetch·RabbitMQ 대기 메시지 상한(max-length / max-length-bytes) 구체화다.
BE 발행 배치와 큐 상한은 다른 설정이다. 유입을 예상하기 어려우므로 임의 상한을 정해 적용하지 않고
측정 → 결과 검토 → 필요한 설정만 조정 → 재측정 순서로 진행한다. 적체 시 재시도·차단·복구 정책은 후속 범위다.

먼저 W=8/P=8을 고정하고 88건/분을 실행한다. 실행은 사용자가 직접 한다.

```bash
bash scripts/profiling-k6/run.sh up
bash scripts/profiling-k6/run.sh sustained --rate 88 --warmup 120 --duration 600 --repeat 3
```

88건/분 결과를 검토한 다음 120건/분을 별도로 실행한다. 두 조건을 한꺼번에 시작하지 않는다.

```bash
bash scripts/profiling-k6/run.sh sustained --rate 120 --warmup 120 --duration 600 --repeat 3
```

- 매 반복은 동일 프로세스·MQ 런타임에서 워밍업 2분 → 워밍업 요청 결과·DB SETTLED 완료 확인 → 본 측정 10분 → 회복 관찰 순서다.
- 워밍업의 요청 ID·사용자 ID·k6 로그·결과 요약은 분리한다. 완료가 확인되지 않으면 본 측정을 시작하지 않는다.
- 워밍업과 본 측정은 같은 분포·seed·유입률을 사용한다. 워밍업 처리량·지연·카운터는 본 측정에서 제외한다.
- 반복별 프로세스는 새로 만든다. 기본 총 36분의 부하 주입에 단계 사이 회복·기동 시간이 추가된다.
- 적체가 있어도 측정을 계속하고 원장을 보존한다. 적체 관측만으로 실패 처리·자동 재시도·큐 삭제·설정 변경을 하지 않는다.
- 발행 오류·결과 누락·DB 완료 불일치·관측 실패는 성능 관측과 구분해 표시한다. 정합성 실패 시 다음 반복을 중단한다.

`results/<UTC시각>-sustained-rate88-<ID>/`에 통합 report.md·comparison.csv/json을 저장한다.
각 `r01-w8-p8/` 등의 폴더에는 본 측정 원본, `warmup/`에는 워밍업 requests.json·publish.log·k6-summary.json·summary.json을 저장한다.
공통 events.jsonl의 `phase` 값은 warmup 또는 measurement다. 이전 결과 파일은 바꾸지 않는다.

추가 지표:

| 지표 | 계산·범위 |
| --- | --- |
| 마지막 5분 미완료 증가율 | (본 측정 종료 미완료 - 종료 5분 전 미완료) / 5. 요청·최종 결과 원장 기준 |
| Ready·Unacked 최고값 | 본 측정 Management API 표본. 갱신 지연으로 실행 경계의 이전 통계가 섞일 수 있음 |
| Ready 본문 크기 최고값 | Management API가 제공할 때만 기록. 미제공이면 미수집 |
| 발행~분석시작 p95 | HTTP·큐·DB 전처리 포함. 순수 큐 대기라고 부르지 않음 |
| 요청 본문 평균 바이트 | k6가 실제 JSON 본문 UTF-8 바이트 수 기록 |
| CPU 평균 코어 | 분석 프로세스 user+system 시간 / 단계 경과시간. 회복 관찰 포함 |
| DB 풀 최고 사용 | 연결 풀 checked-out 표본. 연결 대기시간은 아님 |
| 관측 최대 공백 | 본 측정 로컬 원장 표본 사이 최대 시간. 15초 초과는 관측 유효성에 표시 |

`intervals.csv`는 본 측정 1분별 유입·완료·미완료와 해당 분에 발행한 요청의 완료 p95/p99·표본 수를 기록한다.
해당 요청의 회복 중 완료도 지연 통계에 포함한다. 성공 표본 지연과 미완료 수를 함께 읽는다.

`load_observation`은 `ACCUMULATION_OBSERVED` 또는 `NO_NET_GROWTH_OBSERVED`로 증가 여부를 기술한다.
짧은 실행/워밍업 부족은 `INSUFFICIENT_DATA`, 정합성·관측 실패는 `INVALID_MEASUREMENT`다.
이는 운영 성능 통과 판정이 아니다. 10분간 증가하지 않았다고 모든 부하를 감당한다는 뜻도 아니다.
기존 `verdict=PASS`는 데이터 정합성 기준이며 적체 여부와 독립적이다.

큐 상한 검토는 실측 처리량·Ready 누적량·본문 크기·허용 대기시간을 함께 사용한다.
실제 AI의 안정 처리량과 허용 대기시간이 아직 확정되지 않아 상한을 자동 산정·적용하지 않는다.
단순 관측 예로 실측 110건/분·대기 허용 5분이면 약 550건을 검토할 수 있지만, 현재의 운영 확정값은 아니다.
Ready 본문 바이트는 Unacked·메시지 속성·브로커 메모리 전체를 포함하지 않는다.

## 4단계: 순간 집중 유입

큐 상한을 정할 근거를 수집한다. 워커 8·프리페치 8을 고정하며 실제 모델 호출이나 상한 정책 변경은 하지 않는다.
준비 부하만 끝까지 처리한 뒤, 평상시→집중→회복은 한 번의 k6 실행으로 연속 주입한다.
구간 사이에 큐를 비우거나 소비자를 재시작하지 않는다.

```bash
bash scripts/profiling-k6/run.sh up
bash scripts/profiling-k6/run.sh burst --rate 88 --warmup 120 --baseline-seconds 180 --burst-rate 220 --burst-seconds 30 --recovery-seconds 300 --repeat 3
```

| 구간 | 목표 유입 | 시간 |
| --- | --- | --- |
| 준비 부하 | 분당 88건 | 120초 후 해당 작업 완료 확인 |
| 평상시 | 분당 88건 | 180초 |
| 집중 유입 | 분당 220건 | 30초 |
| 회복 관찰 | 분당 88건 유지 | 300초 |
| 최종 정리 | 유입 중단 | 최대 900초 |

분당 220건은 평상시의 2.5배인 비교용 시험 조건이며 실제 사용자 유입 예측값이 아니다.
기본 3회 실행이며 부하 주입만 31분 30초다. 준비 부하·최종 정리의 완료 확인과 실행 준비 시간이 추가된다.
`--duration` 대신 위 세 구간 시간 옵션을 사용한다. 결과를 확인한 뒤 집중 유입 시간 또는 강도 중 한 항목만 변경한다.

결과는 `results/<시각>-burst-rate220-seconds30-<ID>/`로 묶는다.
기존 통합·개별 보고서와 Prometheus 자동 보존을 재사용하고 다음 자료를 추가한다.

- `load-plan.json`: 목표 유입·시작 시각·구간별 입력 할당
- `burst-stages.csv`: 평상시·집중 유입·회복 중 새 요청의 발행·성공·미완료·완료 지연·대기 최고 표본
- `summary.json`의 `burst`: 기준 지연·기준 미완료와 유입 유지 중 회복 관측 시간
- `publish.log`: 요청별 원래 주입 구간과 실제 구간 시작 시각
- Grafana 목표 유입 속도 패널: 준비 부하·본 측정의 목표 유입 변화. 기존 큐·처리량 패널과 함께 조회

구간별 요청 ID 영역을 분리하므로 시나리오가 바뀌어도 다른 구간의 요청을 재사용하지 않는다.
각 구간의 실제 발행 횟수를 검사하며, 한 구간이 누락됐는데 합계만 맞는 실행을 통과시키지 않는다.
목표 유입 그래프의 전환 시각은 실행 도구 기준의 근삿값이다. 정확한 구간 집계는 k6의 구간 시작 시각을 사용한다.

회복 관측은 큐가 한 번 0이 되는 시점이 아니다.

1. 집중 직전 60초의 완료 요청 지연 95백분위수와 5초별 미완료 수의 95백분위를 기준으로 잡는다.
2. 회복 중 60초 창을 5초씩 이동하며 새 회복 요청 완료 표본이 10개 이상인지 확인한다.
3. 그 창의 완료 지연 95백분위수가 기준의 110% 이내이고, 모든 5초별 미완료가 기준+1건 이내인 첫 창의 종료 시점을 기록한다.

이는 실험용 비교 기준이며 운영 지연 약속·실패 정책이 아니다. 평상시 기준 표본이 10개 미만이면 판정을 보류한다.
회복 관찰 5분 동안 복귀하지 않으면 그대로 기록하고 유입을 중단해 최종 정합성을 확인한다.
최종 정리에서만 큐가 비었다고 ‘평상시 유입 유지 중 회복’으로 표시하지 않는다.
마지막 5분 미완료 변화는 본 실험에서는 회복 구간의 변화이므로 지속 유입 용량 판정에 쓰지 않는다.
적체나 회복 미관측만으로 실험을 실패 처리하지 않으며, 발행 오류·누락·관측 공백은 별도로 확인한다.

## 대시보드

- Grafana: http://127.0.0.1:23001/d/profiling-bench (`bench` / `bench-local-only`)
- Prometheus: http://127.0.0.1:29090
- 테스트 RabbitMQ: http://127.0.0.1:25673 (`bench` / `bench-local-only`)

전용 포트·Compose project·볼륨을 사용한다. 관리 포트는 localhost로 제한한다.
Prometheus는 실행 중 호스트의 28765 포트를 5초마다 수집한다. 해당 포트는 Docker가 읽도록
전체 인터페이스에 열리며 합성 지표만 제공한다. 실행 종료 후 닫히고 target DOWN은 정상이다.
대시보드 시간 범위를 실행 시간에 맞추고 run_id를 선택한다. No data를 0으로 바꾸지 않는다.
새 실행은 `phase`에서 measurement 또는 warmup을 선택한다. phase 라벨이 없던 과거 실행은 All로 확인한다.
큐 통계는 RabbitMQ 관리 통계 갱신 지연이 있다. 정확한 완료 수·정합성은 보고서의 원장과 DB를 기준으로 한다.

## 결과

### 포트폴리오용 Prometheus 장기 보존·Grafana 재표시

기존 summary.json은 집계이고 events.jsonl은 애플리케이션 이벤트 원장이다. 두 파일만으로
당시 Prometheus가 수집한 시계열 전체를 보관한 것은 아니다. 새로 시작하는 실행은 정리 단계에서
run_id에 해당하는 `profiling_bench_*`의 실제 저장 표본을 압축 JSON으로 별도 내보낸다.
워밍업·본 측정·회복의 수집된 표본을 포함하고, query_range로 재표본화하지 않는다.

| 보존 파일 | 내용 |
| --- | --- |
| `prometheus/metrics.json.gz` | Prometheus API의 실제 float 표본·원래 시각·모든 시리즈 라벨 |
| `prometheus/dashboard.json` | 내보내는 시점의 대시보드 구성 |
| `prometheus/prometheus.yml` | 수집 설정 |
| `prometheus/manifest.json` | run_id, 조회·실제 표본 시간 범위, 표본 수, SHA-256 체크섬, 보존 범위·제약 |
| `prometheus-export-status.json` | SAVED 또는 ERROR. 결과 PASS와 별개로 확인 |

이미 끝난 실행도 Prometheus에 데이터가 남아 있을 때 다음 명령으로 보존한다.
완료 summary가 있는 실행만 대상으로 하며 진행 중인 실행은 제외한다. 저장된 파일은 체크섬을 확인하고 재사용한다.
보존 기간이 지나 데이터가 사라졌거나 수집되지 않은 실행은 성공으로 표시하지 않는다.

```bash
bash scripts/profiling-k6/run.sh archive
```

특정 실험 묶음만 보존하려면 다음과 같이 지정한다.

```bash
bash scripts/profiling-k6/run.sh archive --results-dir scripts/profiling-k6/results/20261112T013617Z-sustained-rate88-15cf4ffd
```

현재 실행 중이던 프로세스는 수정 전 코드를 사용하므로 끝난 뒤 archive를 한 번 더 실행한다.
새로 시작하는 실행은 자동 저장한다. Prometheus가 오프라인이면 측정 결과는 보존하고 저장 실패만 기록한다.

나중에 Prometheus의 7일 보관 기간이 지났거나 기존 컨테이너가 없어도 보존 파일에서 다음처럼 복원한다.

```bash
bash scripts/profiling-k6/run.sh restore --results-dir scripts/profiling-k6/results/20261112T013617Z-sustained-rate88-15cf4ffd
```

전체 보존 실행을 함께 띄우려면 다음 명령을 사용한다.

```bash
bash scripts/profiling-k6/run.sh restore
```

복원은 체크섬 검증 → OpenMetrics 변환 → promtool로 별도 TSDB 생성 → 별도 Prometheus/Grafana 실행 순서다.
기존 테스트 모니터링(23001/29090), RabbitMQ·DB를 변경하지 않는다.
포트폴리오용 화면은 http://127.0.0.1:23002/d/profiling-bench 이고 로그인은 `bench` / `bench-local-only`다.
원래 실행 시각으로 열리며 run_id·phase로 실행을 선택한다. 예전 무라벨 데이터는 phase=All로 조회한다.
복원 Prometheus는 29091을 사용하고 자체 수집은 하지 않는다. 복원 데이터의 시간 만료를 피하도록 100년 보관으로 띄운다.
영구 보존의 원본은 JSON 파일이므로 복원용 TSDB가 없어져도 다시 만들 수 있다.

```bash
bash scripts/profiling-k6/run.sh archive-down
```

archive-down은 포트폴리오용 화면만 중지하며 원본 파일을 지우지 않는다.
복원 작업 파일은 `.archive-runtime/`에 두고 원본 보존 폴더와 분리한다.

보존 한계:

- Prometheus에 실제 수집된 `profiling_bench_*` float 시계열을 보존한다. 모든 서버 지표·TSDB 전체 스냅샷은 아니다.
- stale marker·exemplar·native histogram은 포함하지 않는다. 새 native histogram이 생기면 별도 지원 전까지 저장 실패로 표시한다.
- stale marker 부재로 복원 그래프의 종료 직후 꼬리는 원본의 stale 처리와 다를 수 있다. 실제 측정 구간과 원본 보고서를 함께 읽는다.
- 누락된 scrape·수집하지 않은 지표·짧은 실행 사이의 빈 구간은 복원할 수 없다.
- 최종 성공 건수는 summary.json이 기준이다. 마지막 scrape 전에 끝난 작업은 그래프의 마지막 카운터에 반영되지 않을 수 있다.
- 대시보드는 내보내는 시점의 구성이다. 과거 실행 당시 화면과 반드시 같다는 뜻은 아니다.
- results/는 Git에서 제외된다. 실험 폴더와 실행 스크립트를 함께 외장 디스크 등 다른 저장소에 별도 백업해야 PC 고장에도 보존된다.

복원 방식 근거: https://prometheus.io/docs/prometheus/latest/storage/#backfilling-from-openmetrics-format

`scripts/profiling-k6/results/<UTC시각>-wN-pN-<실행ID>/`:

- `manifest.json`: 설정, seed, 코드 commit, 모의 처리 범위
- `requests.json`, `publish.log`: 생성 입력과 실제 발행 시도·시간·라우팅 판정
- `events.jsonl`: 분석 시작·종료, 결과 수신·실패, 큐 표본
- `k6-summary.json`, `k6.log`: 부하 생성 결과와 dropped iterations
- `summary.json`, `results.csv`, `report.md`: 중복 제외 처리량·완료율·p95·최대 동시 분석·DB SETTLED 대조
- `failure.json`: 도중 중단 시 원인. 미완료 메시지는 남겨 조사한다.
- `intervals.csv`: 본 측정 분 단위 처리량·미완료·도착 분별 지연

`success_per_min_during_load`는 유입 구간의 고유 성공 결과만 센다. 회복 중 완료를 포함해 처리량을 부풀리지 않는다.
완료 지연은 k6 발행 시작부터 수집 원장 기록까지이며 관리 API와 결과 수집 polling 비용도 포함한다.
순수 큐 대기/스레드풀 대기/BE DB 반영 시간을 측정한 값이 아니다.
`PASS`는 발행된 요청이 성공 결과와 DB SETTLED까지 대응했다는 뜻이며 안정 처리량 인증이 아니다.
RSS는 워밍업 포함 해당 Python 프로세스의 최고 사용량이므로 본 측정만의 현재 RSS가 아니다.
한 프로세스에서 도는 기존 matrix의 조합별 독립 RSS도 아니다.
실패·미완료 요청은 별도 집계하며 성공 표본 p95만으로 전체 요청의 지연을 설명하지 않는다.

합성 입력과 로컬 전용 공개 비밀번호만 사용한다. 실제 인증 정보·개인정보를 fixture에 넣지 않는다.
