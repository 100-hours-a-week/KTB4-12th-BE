# AI 프로파일링 일반 전송·실패 처리 — PR 5-1

코드 위치와 역할은 [추천 기능 패키지 구조](recommendation-package-structure.md)를 참고한다.

기준일: 2026-10-07. 구현 이슈는 #170이며 현재 부모 이슈는 같은 BE 저장소의 #87(상품 탐색에 AI 추천순 적용)이다. 이 문서는 현재 구현의 운영 동작을 설명한다. 같은 번호 복구·상품 제외 저장도 PR 5-2 로컬 구현에 반영됐으며 `/ready`는 채택하지 않았다.

## RabbitMQ 전환 준비 — 작업 테이블 추가 (V47)

관련 이슈: #523 (상위 #522). V47은 `profiling_jobs`만 추가하며 기존 HTTP 요청·콜백 로직은 유지한다.

- `recipient_profiles`: 수신자별 현재 상태·요청/분석 버전·디바운스를 관리한다.
- `profiling_jobs`: 요청별 입력 스냅샷·발행/결과 상태·재시도·선점 정보를 보관하는 도메인 전용 Outbox다. 실제 작업 생성과 발행은 후속 PR에서 연결한다.
- `request_id`는 PK, `(profile_id, source_version)`은 UNIQUE이며 `profile_id`는 `recipient_profiles.id`를 참조한다. 발행 후보·선점 만료·완료 기록 정리용 인덱스를 둔다.
- 기존 프로파일·추천 행과 FK는 변경하지 않고 과거 작업 행도 생성하지 않는다. 기존 `retry_count`와 `dispatch_claim_*`는 HTTP 전환 PR까지 유지한다.
- Flyway가 애플리케이션 시작 시 적용한다. 이미 적용된 V47 파일은 수정하지 않고 후속 변경은 새 마이그레이션으로 작성한다.

로컬 MySQL 8.4.11에서 사용자가 V47 성공, 신규 작업 0건, 프로파일 16건(COMPLETED 2·NONE 14), 추천 60건 유지 및 기존 요청·콜백 정상 동작을 확인했다. 운영 DB 적용과 제약조건 거부 사례의 별도 테스트는 미확인이다.

## 작업 모델·디바운스 준비 — PR 2-1·2-2 (#524)

- `ProfilingRequestMessage`는 공통 정보와 기존 `AiProfileRequest`를 감싼다. 최초 직렬화한 JSON을 `ProfilingJob`에 고정 저장하고, 재발행은 저장된 UTF-8 바이트를 재사용한다.
- 최종 JSON 256 KiB 초과는 원문을 절단하지 않고 `BLOCKED / INPUT_TOO_LARGE`로 보관한다. 직렬화 자체가 실패하면 준비 트랜잭션을 롤백한다.
- `ProfilingJobPreparationService`는 사용자 → 프로파일 → 이전 작업 순으로 잠근 뒤 디바운스 확인·버전 증가·작업 저장·변경 대기 정리·PENDING을 한 트랜잭션에서 처리한다.
- 잠금 전에는 수신자 ID만 조회한다. 엔티티를 미리 읽어 JPA 1차 캐시에 과거 상태가 남지 않도록 한다. 입력 조회는 READ_COMMITTED에서 수행한다.
- READY·CLAIMED·RETRY_WAIT의 미확인 발행이 있으면 새 변경을 보존하고 기다린다. BLOCKED 중 새 변경은 새 ID·버전으로 준비하고 기존 작업은 오류·본문을 보존한 SUPERSEDED로 기록한다. PUBLISHED는 신규 작업 생성을 막지 않는다.
- 비선호·취향·리뷰 생성/수정/삭제는 사용자 → 프로파일 잠금 규칙에 참여한다. 리뷰 수정·삭제가 새 분석을 예약하지 않는 기존 트리거 정책은 유지한다.
- 입력 조립은 `ProfileRequestFactory`로 공통화하며 기존 HTTP 발송·복구도 이를 사용한다. 기존 중간 버전 콜백 정책은 유지한다.
- 신규 준비 서비스는 스케줄러에서 호출하지 않는다. 실제 작업 생성 활성화·MQ 발행은 PR 3-3에서 HTTP 경로와 함께 전환한다. 결과 Consumer·ACK·결과 상태 반영은 후속 범위다.

검증 코드는 `ProfilingMessageSerializerTest`, `ProfilingJobTest`, `ProfilingJobPreparationIntegrationTest`에 둔다. MySQL에서 중복 제약·원자적 롤백·동시 준비·입력 변경 경쟁을 검증한다.

## 테스트 환경과 CI 검증

프로파일링 시간 경계 테스트는 `@SpringBootTest(properties=...)`에서 quiet-period=1h·maximum-window=6h를 명시한다. 개발 셸의 APP_AIPROFILE_QUIETPERIOD·APP_AIPROFILE_MAXIMUMWINDOW가 application.yaml보다 우선하므로, 개발용 10초·60초 설정이 테스트의 시간 가정을 바꾸지 않도록 한다.

상품 검색 비활성화 테스트는 실제 관리 포트의 health를 조회한다. 모니터링 보안 테스트는 외부 ES health를 비활성화해 인증·포트 검증을 외부 ES 가용성과 분리한다. 운영 health 설정은 변경하지 않는다.

GitHub Actions는 Gradle 의존성 다운로드가 Maven Central 429로 실패했을 때만 최대 3회(20초·40초 대기) 재시도한다. 컴파일·테스트 실패와 그 외 오류는 즉시 전파한다.

## 전송 흐름

1. 스케줄러의 한 번 실행(틱) 시작에 AI `/health`를 한 번 확인한다.
2. 정상일 때만 디바운스가 경과한 후보를 batch-size만큼 조회한다. PENDING 중에도 신규 변경이 있으면 후보가 될 수 있다.
3. 준비 트랜잭션에서 행을 잠그고 조건을 재검증한 뒤 sourceVersion을 증가시키고 변경 스냅샷·요청을 구성한다.
4. 준비 트랜잭션 커밋 후 AI에 POST `/api/internal/v1/ai/profile/extract-and-pool`을 보낸다.
5. 202 접수 또는 HTTP 실패를 별도 트랜잭션으로 반영한다. DB 반영 오류는 전파하며 이미 커밋한 번호를 되돌리지 않는다.

새 변경만으로 기존 추천을 삭제하거나 자동 FAILED로 전이하지 않는다. AI의 완료 결과는 기존 내부 콜백 POST `/api/internal/v1/recipients/{recipientUserId}/profile`로 반영한다. 202는 완료가 아닌 접수 성공이다.

## 일반 실패 정책

| 상황 | 처리 |
| --- | --- |
| 첫 실패 | last_changed_at·window_started_at을 모두 실패 반영 시각으로 갱신하고 retry_count=1 |
| 디바운스 경과 | 새 sourceVersion으로 한 번 더 전송. 번호 생성은 횟수를 유지 |
| 두 번째 실패 | 두 변경 시각 NULL·retry_count=0, 변경 대기 포기 WARN |
| 사용자 신규 변경 | 변경 시각 기록·retry_count=0으로 새 기회 부여 |
| 해당 변경의 202 | 현재 번호·변경 스냅샷 검증 후 변경 대기·횟수 정리 |
| HTTP 중 신규 변경·새 번호 | 이전 실패가 번호·스냅샷과 불일치하면 반영 생략 |
| 늦은 완료 콜백 | 신규 변경·실패 재시작이 남으면 변경 시각과 횟수 보존 |

디바운스 재시작은 1회이며 최초 발송 포함 최대 2회다. DB CHECK 0~2는 저장 상한으로 유지하며 일반 실패 정책 상한과 구분한다. 변경 대기 포기는 사용자 비선호나 기존 추천 삭제를 의미하지 않는다.

## 상태 확인과 틱 종료

무인증 GET `/health`의 HTTP 200, catalog.active=true, store.connected=true를 모두 요구한다. 두 필드는 JSON Boolean이어야 한다. 누락·문자열 "true"·HTTP 오류·파싱 실패·연결 실패는 비정상이다. 비정상 틱에서는 후보 조회·번호 생성·POST 없이 변경 시각과 번호를 유지한다.

| POST 실패 | 대상 처리 | 다음 후보 |
| --- | --- | --- |
| 연결·응답 타임아웃·500·503 | 일반 실패 반영 | 틱 전체 종료 |
| 400·401·정의되지 않은 응답 | 일반 실패 반영 | 다음 후보 진행 |

`isRetryable()`은 틱 종료 기준이며 디바운스 재시작 대상과 구분한다. 400·401도 일반 실패 재시작 대상이다. 연결·타임아웃은 COMMUNICATION_ERROR, 직렬화·응답 처리·계약 오류는 INVALID_RESPONSE로 분류한다. PR 5-2에서 복구 루프를 추가해도 틱 종료를 우회하지 않는다.

## 설정

| 설정 | 기본값·동작 |
| --- | --- |
| quiet-period | 60s. 마지막 변경 이후 조용한 시간. 실패 재시작에도 적용 |
| maximum-window | 300s. 최초 변경 창 이후 최대 대기. 실패 재시작은 창도 다시 시작 |
| dispatch-interval | 1m, AI_PROFILE_DISPATCH_INTERVAL |
| batch-size | 100, AI_PROFILE_BATCH_SIZE |
| recovery-batch-size | 50, AI_PROFILE_RECOVERY_BATCH_SIZE. 0이면 복구 생략 |
| POST connect-timeout / read-timeout | 3s / 10s, AI_PROFILE_CONNECT_TIMEOUT / AI_PROFILE_READ_TIMEOUT |
| health 연결·읽기 타임아웃 | 각각 min(기존 설정, 2s). 토큰 없는 별도 RestClient |
| scheduling-enabled | false. 실행 환경에서 명시적으로 활성화 |

quiet-period와 maximum-window는 기존 Spring 설정으로 재정의할 수 있다. 복구 상한·별도 재시도 Scheduler·next_retry_at·새 컬럼은 PR 5-1에 추가하지 않는다. 디바운스 시간은 장애 중 결과 반영을 보장하는 SLA가 아니다. 스케줄러는 기본 1분 간격으로 실행하므로 시간 조건을 충족한 뒤 다음 틱에 전송한다.

## 로그와 오류

실패 반영 트랜잭션 커밋 후 recipientUserId·sourceVersion·failureType·action을 WARN에 남긴다. action은 DEBOUNCE_RESTARTED·ABANDONED·SKIPPED다. 토큰·이메일·전체 요청 및 응답 Body·비선호·취향·리뷰 원문·원인 예외 전문은 기본 실패 로그에 넣지 않는다. DB 반영 실패를 정상 재시작으로 기록하지 않는다.

## 검증

2026-10-07 관련 단위·DB 통합 테스트 88개가 통과했다. 테스트 준비 코드 보완 후 직접 영향 테스트 28개와 전체 테스트 1,082개도 모두 통과했다. 실패·오류·건너뜀은 0개다. 운영·테스트 컴파일과 변경사항 공백 검사도 통과했다.

- ProfileDispatchPolicyTest: 실제 엔티티·준비 로직을 사용해 첫 실패→새 번호 재전송→두 번째 실패 포기, 신규 변경 보호, 틱 종료, DB 오류 전파, 로그를 검증한다. Repository는 Mock이다.
- ProfileDispatchServiceIntegrationTest: 실제 DB에서 번호 커밋과 HTTP 트랜잭션 분리·202 반영을 확인한다.
- ProfileCallbackTransactionIntegrationTest: 사용자 변경→재시작→횟수 증가로 준비하고 retry_count=1을 확인한다. 변경 대기가 남은 완료 콜백의 횟수 보존과 실패 시 전체 스냅샷 롤백을 검증한다.
- AiProfilingHealthClientTest·AiProfilingClientTest와 기존 엔티티·Repository 테스트는 상태 판정·오류 분류·PENDING 후보·늦은 응답 보호를 확인한다.

이 테스트 결과는 실제 AI 서버의 보관 결과 재전송·같은 번호 재분석 계약 검증을 대신하지 않는다. PR의 대상 브랜치는 develop이며 이슈는 병합과 완료 조건 확인 후 종료한다.

## PR 5-2 복구·콜백 저장

일반 전송 후 변경 없는 오래된 PENDING을 pending_since·ID 순서로 조회하고 행 잠금 안에서 재검증한다. PreparedRecoveryDispatch는 같은 번호와 대기 시각 스냅샷을 보관한다. HTTP는 트랜잭션 밖에서 호출하고 성공·실패 모두 같은 번호의 PENDING과 대기 스냅샷이 일치할 때 pending_since만 갱신한다. retry_count와 신규 변경 시각은 유지한다.

AI_PROFILE_RECOVERY_BATCH_SIZE 기본 50, 0이면 복구 생략. 일반·복구 Batch 합계는 첨부 기준 200 이하로 검증한다. 여러 인스턴스 합산이나 실제 AI 대기열 잔량 제어는 별도다. 복구 간격은 maximum-window(기본 300s)이며 누적 복구 횟수 제한은 없다. 일반 전송의 틱 중단을 우회하지 않는다.

콜백은 없는·삭제된 상품만 제외하고 입력 순서대로 rank_order를 1부터 다시 매긴다. 전부 제외·빈 배열도 기존 추천을 삭제하고 0행·COMPLETED로 저장한다. 중간 번호 콜백은 최신 요청 상태를 유지하며 같은 번호는 멱등 성공, 저장본보다 오래된 번호는 409다. 삭제·저장·상태 변경은 한 트랜잭션이며 실제 DB 오류는 전체 롤백한다.

제외 ID·수신자·번호는 WARN으로 기록하고 응답은 data: {}를 유지한다. DTO 검증과 DB FK·UNIQUE는 유지한다. 새 Migration은 없다. 실제 AI 계약 일치와 다중 실행자 선점은 별도 범위다.

### PR 5-2 최종 검증 — 2026-10-07

운영·테스트 컴파일과 전체 테스트 1,122개가 통과했다. 실패·오류·건너뜀은 0개다. 관련 테스트 144개 통과 후 설정 경계 테스트와 전체 실행 시 후보 데이터 간섭 보완을 포함해 다시 검증했다. 외부 AI 호출은 Stub으로 대체하고 실제 MySQL DB를 사용했다. 실제 AI 서버 계약 검증은 수행하지 않았다.
