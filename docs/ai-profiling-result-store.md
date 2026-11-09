# 프로파일링 결과 원자적 저장 — PR2

관련 작업: [BE #582](https://github.com/100-hours-a-week/KTB4-12th-BE/issues/582),
[상위 #522](https://github.com/100-hours-a-week/KTB4-12th-BE/issues/522).
선행 계약·해시·V53: [PR1 결과 계약](ai-profiling-result-contract.md).
설계 기준: [BE Consumer](https://app.notion.com/p/4121885e7728806696a5d518fadebca7).

## 범위

검증된 `ProfilingResultMessage`를 받아 처리 결론을 반환하는 DB 서비스다.
RabbitMQ 없이 호출할 수 있으며 Listener·ACK·DLQ·Consumer 중지/재개는 포함하지 않는다.
새 테이블·컬럼·마이그레이션은 추가하지 않는다.

| 클래스 | 변경·책임 |
| --- | --- |
| ProfilingJobRepository | Projection으로 잠금 대상을 찾고 requestId로 작업 잠금 조회 |
| ProfilingResultService | 사용자 → 프로필 → 작업 잠금 후 대응·상태·해시·버전을 검증하고 원자적 저장 |
| ProfilingJob.finishFromResult | 최종 상태·해시·종료 시각, PUBLISHED 보완과 선점/예약 해제 |
| ProfileRecommendationWriter | HTTP/MQ 공용 상품 제외·순위·추천 교체·프로필 완료·이벤트 발행 |
| ProfileCallbackService | 기존 HTTP 검증/예외 계약 유지, 추천 교체만 Writer에 위임 |
| AiRecommendedProductCache | 커밋 후 과거 이벤트가 버전을 덮지 않도록 활성 버전 키 무효화 |

`RecipientProfile`의 완료·실패 전이와 `ProfilingPublishStore`의 WAITING/토큰 보호 조건은 재사용한다.

## 트랜잭션과 결론

`save()`는 REQUIRES_NEW·READ_COMMITTED로 독립 커밋 경계를 갖는다.
Spring 서비스 프록시를 통해 호출하며 이미 같은 사용자/프로필/작업을 잠근 외부 트랜잭션에서 호출하지 않는다.
Writer는 MANDATORY로 동일 트랜잭션에 참여한다.
추천·프로필·작업 종료·fingerprint는 함께 커밋/롤백한다. DB 예외를 격리 결론으로 바꾸지 않는다.

| 결론 | 의미 |
| --- | --- |
| RECORDED | 최초 결과를 작업에 기록. 추천 변경 여부는 별도 반환 |
| DUPLICATE | 같은 최종 결과이며 재반영하지 않음 |
| IGNORED | 식별 검증을 통과한 SUPERSEDED. 기존 상태·해시·추천 유지 |
| QUARANTINE | 식별/버전/상태 불일치, 상충 결과, 종료·해시 NULL 등. 기존 업무 데이터 유지 |

QUARANTINE은 격리가 필요하다는 판단이며 이 서비스가 DLQ 전송·ACK를 수행한다는 뜻은 아니다.
결론 로그는 afterCompletion의 COMMITTED 분기에서 남기고 본문·AI 설명은 기록하지 않는다.

## 버전·중복 정책

- 저장 v6·최신 요청 v8 상태에서 v7 성공: 추천/분석 버전만 v7로 진행하고 v8 대기·새 변경 유지.
- v8 반영 후 WAITING v7 결과: v7 작업만 실제 성공/실패로 종료.
- 최신 실패: 프로필 FAILED·pendingSince 해제, 기존 추천·분석 버전·새 디바운스 변경 유지.
- 동일 결과: createdAt·오류 설명이 달라도 같은 비교 해시면 no-op. 상충 결과는 기존 결과를 덮지 않음.
- 기존 COMPLETED/FAILED·해시 NULL: 임의 backfill 없이 격리.
- 추천 필터링 전에 원본 결과의 fingerprint를 계산. 없는/삭제 상품은 제외하고 순위를 1..n으로 저장.
- 빈 추천과 전부 제외된 추천은 성공이며 기존 추천을 비움. 실패는 기존 추천을 보존.

## 결과 선도착·Publisher 경합

유효 결과를 전달 증거로 PUBLISHED 처리하고 claim_token·claimed_at·next_attempt_at을 해제한다.
기존 published_at은 보존하고 누락 시 BE 처리 시각을 보완하며 publishedAtInferred를 반환/관측한다.
attempt_count·first_failed_at은 발행 이력으로 보존한다.
최종 성공은 이전 발행 오류를 정리하고 최종 실패는 내부 진단 AI_RESULT_FAILED를 기록한다.
AI 오류 코드 자체는 길이 제한 미합의 상태이므로 VARCHAR(100)에 그대로 넣지 않고 해시 비교에 포함한다.

현재 Publisher 성공·실패·반납은 ownedRow()에서 WAITING/CLAIMED/토큰을 잠금 안에서 확인한다.
만료 회수도 WAITING 작업만 대상으로 하므로 종료 후 갱신을 거부한다. 보호 조건을 중복 구현하지 않는다.
수동 복구 역시 사용자 → 프로필 → 작업 순서로 상태를 재검증한다.

시각은 하나의 Instant를 마이크로초로 캡처한 뒤 기존 기준을 따른다.
finished_at은 서울 시각(기존 이력 정리 기준), published_at 보완값은 UTC(기존 Publisher 기준)다.
AI createdAt을 BE 종료 시각이나 실제 발행 확인 시각으로 사용하지 않는다.

## 커밋 후 캐시 보완

DB 커밋 순서와 AFTER_COMMIT 이벤트 처리 완료 순서는 다를 수 있다.
v7 이벤트가 v8보다 늦게 Redis SET을 수행하면 DB는 v8인데 캐시가 v7이 되는 경합을 테스트로 재현했다.
이벤트에서는 활성 버전 키를 DELETE하고 기존 조회 경로가 DB 버전을 재로딩하게 변경했다.
늦은 이벤트가 버전 값을 덮어쓰지 않으며 다음 첫 조회의 DB 조회 1회가 추가되는 절충이다.
기존 추천 목록/응답 캐시의 버전별 키·TTL은 유지한다. Redis 실패는 이미 커밋된 DB 결과를 롤백하지 않는다.

## 테스트

신규 결과 저장 통합 45건·엔티티 6건·캐시 2건, 총 53건을 추가했다.
기존 MySQL 8.4·Redis Testcontainers 기반을 사용하고 브로커 없이 결과 저장을 호출한다.

- 정상/중복/상충/종료 NULL/SUPERSEDED/식별·버전·상태 불일치.
- 중간·오래된 결과, 최신 실패, 새 변경 보존, 상품 제외·빈 결과.
- 추천 저장/작업 flush/커밋 전 실패 전체 롤백, 커밋 후 이벤트·로그 경계.
- 동시 최초 수신·버전 역전, 결과 선도착과 Publisher 성공/실패/반납/만료 회수.
- 수동 복구와 결과 경합, 이력 정리 후 늦은 결과, Redis 장애·캐시 이벤트 역전.

```bash
./gradlew :main-backend:test --tests '*ProfilingResultServiceIntegrationTest' --tests '*ProfilingJobTest' --tests '*ProfileCallback*' --tests '*AiRecommendedProductCacheTest' --offline
./gradlew :main-backend:test :payment-server:test --offline --continue
```

실제 MQ 결과 소비·AI 연동·운영 환경 장애 검증은 후속 PR 범위다.

### 2026-11-09 검증 결과

- 관련 테스트 141건 통과(신규 53건 포함).
- BE 전체 JUnit XML 집계: 총 2,067건, 성공 2,058건, 실패/오류 0건, 환경 조건부 제외 9건.
- 제외는 기존 Elasticsearch 조회/동기화 통합 테스트 9건이다.
- 결제 모듈은 cleanTest 후 실제 재실행하여 52건 전부 통과.
- 두 모듈 build(-x test)·최종 테스트 소스 컴파일·git diff --check 통과.
- 운영 DB·실제 AI/MQ 연동을 실행하거나 커밋·PR을 생성한 결과는 아니다.
