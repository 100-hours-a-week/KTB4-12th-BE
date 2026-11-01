# AI 추천 목록 Redis 전후 부하 비교

이 환경은 같은 k6 요청과 전용 MySQL 데이터로 `perf/ai-product-list-no-redis`와
`perf/#502-recommendation-response-cache`의 실제 동작을 비교합니다. 브랜치를 checkout하지 않습니다.
before 이미지는 기준 브랜치의 커밋에서 만들고, after 이미지는 현재 after 브랜치 작업 트리에서 만듭니다.
after의 커밋과 소스 SHA-256은 `runtime.tmp/active-backend.json`에 남습니다.

## 준비와 백엔드 선택

backend 저장소 루트에서 실행합니다. Docker Desktop과 로컬 `k6`가 필요합니다.

```sh
python3 performance/recommendation-load/environment.py init
python3 performance/recommendation-load/environment.py backend before
```

`init`은 전용 MySQL·Redis·Prometheus·Grafana 및 exporter를 시작합니다. `backend before`는
기준 브랜치 소스 이미지를 만들고, 그 백엔드를 실행한 뒤 같은 전용 DB에 fixture를 준비합니다.
앱 이미지 전환은 backend 명령으로 합니다. 다른 Compose 프로젝트의 컨테이너와 볼륨은 건드리지 않습니다.

fixture는 테스트 계정 5명(사용자 2~6), 수신인 1명(사용자 1), 추천 상품 30개, 카탈로그 10,000개입니다.
모든 k6 요청은 `GET /products?sort=AI_RECOMMENDED&recipientUserId=1` 첫 페이지를 반복합니다.
JWT는 준비 도구가 현재 테스트 계정 ID에 맞게 발급하므로 따로 로그인하거나 토큰을 만들 필요가 없습니다.

Grafana는 `http://127.0.0.1:3302`에 있습니다. 로그인은 `admin`; 비밀번호는
`performance/recommendation-load/runtime.tmp/.env`의 `GRAFANA_ADMIN_PASSWORD` 값입니다.

## 부하 명령

각 명령은 10 RPS로 2분, 목표까지 2분 증가, 목표 유지, 1분 감소, 2분 회복 순서입니다.
`PROFILE=discover`는 목표 유지 2분으로 총 9분, `PROFILE=compare`는 목표 유지 10분으로 총 17분입니다.
이 안내는 고정 VU 모델인 `scenario-vus.js`를 사용합니다. 목표 VU까지 올려 유지하되, 응답이 느려지면
실제 RPS가 지정한 값보다 낮아질 수 있습니다. 전후 비교에는 동일한 RPS·VU와 이 스크립트를 사용합니다.

목표 RPS별 병목 탐색은 before 백엔드에서 한 명령씩 실행합니다.

```sh
K6_PROMETHEUS_RW_SERVER_URL=http://127.0.0.1:19092/api/v1/write \
K6_PROMETHEUS_RW_TREND_STATS='p(95),p(99)' K6_PROMETHEUS_RW_PUSH_INTERVAL=1s \
k6 run -e MODE=before -e PROFILE=discover -e RPS=10 -e VUS=100 \
-e RUN_ID=before-discover-10 -o experimental-prometheus-rw \
performance/recommendation-load/scenario-vus.js

K6_PROMETHEUS_RW_SERVER_URL=http://127.0.0.1:19092/api/v1/write \
K6_PROMETHEUS_RW_TREND_STATS='p(95),p(99)' K6_PROMETHEUS_RW_PUSH_INTERVAL=1s \
k6 run -e MODE=before -e PROFILE=discover -e RPS=50 -e VUS=500 \
-e RUN_ID=before-discover-50 -o experimental-prometheus-rw \
performance/recommendation-load/scenario-vus.js
```

같은 형식으로 `RPS=100 VUS=1000`, `RPS=200 VUS=2000`, `RPS=300 VUS=3000`을 실행합니다.
각 단계 전에 실제 요청 RPS·p95/p99·오류율·누락 반복·Hikari 대기·MySQL 지표를 살펴 다음 단계로 갈지 결정합니다.

마지막 정상 단계와 최초 저하 단계는 양쪽 백엔드에서 `PROFILE=compare`로 실행합니다.

```sh
python3 performance/recommendation-load/environment.py backend after
python3 performance/recommendation-load/environment.py prewarm
K6_PROMETHEUS_RW_SERVER_URL=http://127.0.0.1:19092/api/v1/write \
K6_PROMETHEUS_RW_TREND_STATS='p(95),p(99)' K6_PROMETHEUS_RW_PUSH_INTERVAL=1s \
k6 run -e MODE=after -e PROFILE=compare -e RPS=100 -e VUS=1000 \
-e RUN_ID=after-compare-100 -o experimental-prometheus-rw \
performance/recommendation-load/scenario-vus.js
```

비교할 RPS/VUS를 탐색 결과에 맞게 바꾸고, 같은 값을 before/after 양쪽에서 사용합니다.
각 after 측정 직전에 `prewarm`을 실행하세요. 이 명령은 대상 추천 키만 지운 뒤 실제 API를 호출해
첫 응답 전체를 Redis에 적재하고, 다음 요청에서 `X-Test-Cache-Path: hit`을 확인합니다.
응답 JSON을 직접 만들어 Redis에 넣지 않습니다. `k6`의 HIT/MISS 그래프도 이 응답 헤더를 기록합니다.

after 코드의 추천 응답 캐시 HIT는 목록 ID만 반환하는 경로가 아니라 `ProductListResponse` 전체를 반환하며,
추천·상품·썸네일 조회로 이어지는 DB 쿼리를 생략합니다. 같은 수신인·필터·첫 페이지 요청을 반복하는
이번 시나리오에서는 응답 캐시 HIT가 확인되면 이 상품 데이터 조회 경로가 생략된 것입니다.

JWT 검증과 수신인 접근 검사는 응답 캐시 HIT에도 매 요청 수행됩니다. 수신인 활성 상태와 친구 관계는
Redis에 5초 TTL로 캐싱하고, 키가 없거나 만료되면 DB를 조회합니다. 추천 분석 버전도 Redis를 우선 조회하며,
키가 없으면 DB에서 가져옵니다. `prewarm`은 첫 번째 테스트 계정으로만 권한 키를 예열하고 k6는 5개 계정을
순환하므로, 다른 계정의 첫 요청이나 짧은 권한 캐시 만료 시 DB 조회가 남을 수 있습니다. 따라서 after에서
DB 조회가 0건이 아니어도 이상은 아니며, 추천 상품·상품 요약·썸네일 조회가 줄었는지는 응답 캐시 HIT 비율과
추천 목록 DB 조회 지표를 함께 확인합니다.

## 대시보드와 결과 해석

대시보드는 12개 패널로 구성되며 실행 ID·캐시 조건·목표 RPS로 필터링합니다. 응답시간 p95/p99는
k6 요청 전체에서 집계합니다. 12번 패널은 Redis 인스턴스 전체 hit/miss와 k6 응답 헤더에서 확인한
추천 응답 cache HIT/MISS를 따로 표시합니다. 기준 브랜치는 해당 응답 헤더가 없어 `not_instrumented`로 보입니다.

응답 전체 캐시 TTL은 현재 코드에서 30분입니다. `prewarm`이 저장한 시점부터 시간이 계산되므로,
17분 compare 프로필을 끝까지 캐시 HIT로 측정하려면 `prewarm` 후 13분 안에 부하를 시작하세요.
TTL 만료나 Redis 오류로 MISS가 나타나면 대시보드의 응답 캐시 경로와 DB 조회 지표를 함께 확인합니다.
병목 비교는 동일한 `compare` 프로필의 목표 유지 구간을 중심으로 해석하세요.

대시보드는 `http://127.0.0.1:3302/d/ai-recommendation-load`에 있습니다. 실행 후 대표 지표는 터미널과
`performance/recommendation-load/runtime.tmp/results/<RUN_ID>-summary.json`에 같은 JSON 형식으로 출력되고,
전체 k6 집계 자료는 `performance/recommendation-load/runtime.tmp/results/<RUN_ID>.json`에 저장됩니다.
대표 지표에는 실행 ID, 조건, 목표·실제 RPS, 정상 응답 수·처리량, p50/p95/p99, HTTP 오류율,
응답 검증 성공률, 누락 반복 실행, 시작·종료 시각(KST·UTC), 실제 경과시간이 포함됩니다.
개별 요청 원문이나 요청별 로그는 저장하지 않습니다. 실행 중 시계열은 Prometheus/Grafana에서 확인합니다.
CPU·메모리는 Docker Engine stats API에서 컨테이너별로 읽고 CPU 제한량 대비 비율로 표시합니다.
현재 Docker Desktop 응답에는 블록 읽기 바이트는 있지만 읽기 작업 개수(`io_serviced_recursive`)가 없어
읽기 IOPS 패널은 `수집 안 됨`으로 표시됩니다. 읽기 바이트를 IOPS로 환산하지 않습니다.

`experimental-prometheus-rw`는 k6의 실험적 출력 기능입니다. 1초 remote-write 주기를 지정하고,
p95/p99 trend 값을 함께 전송합니다. [Grafana k6 remote write 문서](https://grafana.com/docs/k6/latest/results-output/real-time/prometheus-remote-write/)

이번 비교는 브랜치별 코드와 한 명의 수신인·해당 자원 제한 환경에서의 관측입니다. 반복 재현성이나
운영 규모의 최대 처리량으로 일반화하지 않습니다.
