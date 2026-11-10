# 추천 목록 Redis 성능 비교

한국어 대시보드는 `http://127.0.0.1:3301/d/recommendation-cache-comparison`에 있습니다.
평상시 5분·이벤트 5분을 캐시 미적용, 모두 적중, 최초 조회 70%의 세 조건에서 각각 **한 번** 실행합니다.
환경 준비·60초 예열·잔여 작업 종료 대기는 30분에 포함하지 않습니다.

## 실행

백엔드 저장소에서 실행합니다. Docker Desktop의 CPU·메모리와 Docker 소켓 접근 권한이 필요합니다.

```sh
python3 performance/cache-comparison/control.py prepare
python3 performance/cache-comparison/control.py run
```

`prepare`는 환경과 데이터만 준비하며 부하를 발생시키지 않습니다.
실제 부하는 별도의 `run` 명령을 실행해야 시작합니다.
`serve`는 저장된 수치 또는 측정 전 상태를 제공할 뿐 API를 호출하지 않습니다.

`prepare`는 이전 `gift-recommendation-loadtest`, `gift-recommendation-monitoring` 프로젝트의 컨테이너만 제거합니다.
기존 볼륨과 공용 모니터링·결제 환경은 유지합니다. 새 환경은 `gift-cache-comparison` 프로젝트와 전용 볼륨을 사용합니다.
캐시 미적용은 같은 앱 이미지의 `REDIS_PRODUCT_AI_RECOMMENDED_CACHE_ENABLED=false`로 실행하며 Redis 장애를 만들지 않습니다.

완료 후 결과 exporter는 실행을 유지합니다. Ctrl-C는 exporter만 종료하며 앱·DB·Grafana는 유지됩니다.
저장된 결과를 다시 대시보드에 제공할 때는 다음을 사용합니다. 이는 부하를 다시 실행하지 않습니다.

```sh
python3 performance/cache-comparison/control.py serve
```

Grafana 계정은 `admin`입니다. 생성된 비밀번호는 로컬 `runtime.tmp/settings.json`의 `TEST_GRAFANA_PASSWORD`에서 확인합니다.
해당 파일·토큰 파일·환경 파일은 Git에 포함하지 않습니다. 결과 보고서에도 자격증명을 기록하지 않습니다.

## 일정과 데이터

| 구간 | 초 | 활동 사용자 | 예정 유입량 |
|---|---:|---:|---:|
| 평상시 | 0–300 | 30명 | 1.5 RPS |
| 유입 증가 | 300–330 | 30→200명 | 1.5→20 RPS 선형 |
| 집중 이용 | 330–510 | 200명 | 20 RPS |
| 유입 감소 | 510–540 | 200→30명 | 20→1.5 RPS 선형 |
| 회복 | 540–600 | 30명 | 1.5 RPS |

정해진 유입량을 적분해 4,785개의 시작 시각을 만들고 계정·수신인과 함께 세 조건에서 재사용합니다.
평상시에는 계정 30명에게 순환 배정하고 집중 구간에는 200명에게 순환 배정합니다.
k6 작업자 256개는 시간표를 분담하는 실행 자원이며 활동 사용자 수와 다릅니다.
시작 지연 100ms 초과를 기록하고, 1초 이상 늦은 요청은 뒤늦게 몰아서 실행하지 않고 미실행으로 기록합니다.
응답 제한 시간은 10초이며 자동 재시도하지 않습니다.

최초 조회는 평상시 315건·이벤트 3,035건으로 총 3,350개의 서로 다른 수신인을 사용합니다.
캐시 조회 1,435건은 별도 수신인 100명에게 순환 배정합니다.
모두 적중 조건에서는 전체 3,450명의 버전·추천 목록·첫 페이지 응답을 적재합니다.
혼합 조건에서는 반복 조회 집단 100명의 동일한 키를 적재합니다. 첫 페이지 응답 캐시는
검색어·선택 카테고리·커서 조합별로 분리하며, 서명 이미지 URL 만료 전에 끝나도록 14분 TTL을 둡니다.
알려진 추천 키만 준비 단계에서 삭제하며 요청마다 삭제하거나 `FLUSHALL`을 실행하지 않습니다.

일반 프로필의 API JSON은 바뀌지 않습니다. `cache-comparison` 프로필에서만
`X-Test-Cache-Path: bypass|hit|miss|error|unknown` 헤더를 추가합니다.
`unknown`은 인증·입력 검증 등에서 추천 캐시 경로에 도달하지 못한 요청에도 사용됩니다.

## 8개 패널과 집계 의미

1. 요청 부하 확인: 예정/실제 시작 RPS, 활동 사용자, 미실행 건수
2. 추천 목록 응답시간: 원본 요청의 p50/p95/p99, 혼합 조건의 HIT/MISS
3. 정상 응답 성공률: HTTP 200과 응답 내용 검증을 함께 만족한 비율
4. 초당 정상 처리량: 실제 완료 시각 기준 성공 RPS
5. 추천 캐시 적중 현황: 전체 추천 응답의 HIT/MISS, 별도 Redis 오류
6. DB 조회량 비교: 추천 페이지·권한 관련 논리적 DB 로드, 추천·상품·이미지·권한 SELECT digest 차이, 요청당 관련 SQL, 전체 DB QPS
7. DB CPU 사용률: 2 CPU 한도 기준 평균·관측 최대
8. DB 커넥션 사용과 대기: active/max/pending, 획득 p95, 획득 타임아웃

표는 선택 구간의 세 조건을 비교하고 그래프는 실제 실행 시각을 유지합니다.
기본 표 구간은 이벤트 집중 이용입니다. p95를 평균내거나 수집 누락을 0으로 바꾸지 않습니다.
①의 캐시 적중률은 해당 없음입니다. DB CPU는 Docker Desktop의 컨테이너별 원본을 얻기 위해
Docker Engine stats를 1초마다 읽어 CPU quota로 정규화하며 cAdvisor도 함께 수집합니다.
DB SQL/QPS는 관측 시각의 경계 스냅샷으로 계산하므로 경계 시각을 원본에 보관합니다.
전체 DB QPS에는 모니터링 SQL도 포함됩니다.

수신인 활성 상태는 5초 TTL로 캐싱하고, 친구 관계는 관계 변경 커밋 후 즉시 무효화하며 5초 TTL을 보조로 둡니다.

결과는 `results/<실행 묶음>/report.md`, `summary.json`, `state.json`, `requests-*.jsonl`, `environment.json`에 남습니다.
보고서는 조건별·구간별 Grafana 링크, p95 변화율, 성공률·DB 부하·실제 캐시 경로를 포함합니다.
실패와 부하 생성·데이터·수집 문제를 구분하며, 한 번의 결과를 반복 재현성이나 최대 수용량으로 일반화하지 않습니다.

## 검증

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s performance/cache-comparison -p 'test_*.py'
./gradlew :main-backend:test
```

각 조건은 캐시 상태를 준비한 뒤 최초·캐시 조회 수신인으로 사전 검증을 먼저 합니다(캐시 미적용 2건, 적용 시 최대 4건).
DB fixture 전체와 응답 상품·가격·이미지·커서 검증이 통과해야 60초 예열과 10분 측정으로 넘어가며, 예열 후 캐시 상태를 다시 맞춥니다.
검증 실패 시 다른 필드를 터미널에 표시하고 본 측정은 시작하지 않습니다. 실행 중에는 15초마다 k6 진행 건수를 출력합니다.
부하 종료 후 서버 요청과 풀 대기가 최대 60초 안에 해소되지 않으면 다음 조건을 실행하지 않습니다.
