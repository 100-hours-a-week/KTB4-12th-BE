# AI 프로파일링 일반 전송·실패 처리 — PR 5-1

기준일: 2026-10-07. 구현 이슈는 #170이며 현재 부모 이슈는 같은 BE 저장소의 #87(상품 탐색에 AI 추천순 적용)이다. 이 문서는 현재 구현의 운영 동작을 설명한다. 같은 번호 복구·상품 제외 저장도 PR 5-2 로컬 구현에 반영됐으며 `/ready`는 채택하지 않았다.

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
| quiet-period | 1h. 마지막 변경 이후 조용한 시간. 실패 재시작에도 적용 |
| maximum-window | 6h. 최초 변경 창 이후 최대 대기. 실패 재시작은 창도 다시 시작 |
| dispatch-interval | 1m, AI_PROFILE_DISPATCH_INTERVAL |
| batch-size | 100, AI_PROFILE_BATCH_SIZE |
| recovery-batch-size | 50, AI_PROFILE_RECOVERY_BATCH_SIZE. 0이면 복구 생략 |
| POST connect-timeout / read-timeout | 3s / 10s, AI_PROFILE_CONNECT_TIMEOUT / AI_PROFILE_READ_TIMEOUT |
| health 연결·읽기 타임아웃 | 각각 min(기존 설정, 2s). 토큰 없는 별도 RestClient |
| scheduling-enabled | false. 실행 환경에서 명시적으로 활성화 |

quiet-period와 maximum-window는 기존 Spring 설정으로 재정의할 수 있다. 복구 상한·별도 재시도 Scheduler·next_retry_at·새 컬럼은 PR 5-1에 추가하지 않는다. 디바운스 시간은 장애 중 결과 반영을 보장하는 SLA가 아니다.

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

AI_PROFILE_RECOVERY_BATCH_SIZE 기본 50, 0이면 복구 생략. 일반·복구 Batch 합계는 첨부 기준 200 이하로 검증한다. 여러 인스턴스 합산이나 실제 AI 대기열 잔량 제어는 별도다. 복구 간격은 maximum-window(기본 6h)이며 누적 복구 횟수 제한은 없다. 일반 전송의 틱 중단을 우회하지 않는다.

콜백은 없는·삭제된 상품만 제외하고 입력 순서대로 rank_order를 1부터 다시 매긴다. 전부 제외·빈 배열도 기존 추천을 삭제하고 0행·COMPLETED로 저장한다. 중간 번호 콜백은 최신 요청 상태를 유지하며 같은 번호는 멱등 성공, 저장본보다 오래된 번호는 409다. 삭제·저장·상태 변경은 한 트랜잭션이며 실제 DB 오류는 전체 롤백한다.

제외 ID·수신자·번호는 WARN으로 기록하고 응답은 data: {}를 유지한다. DTO 검증과 DB FK·UNIQUE는 유지한다. 새 Migration은 없다. 실제 AI 계약 일치와 다중 실행자 선점은 별도 범위다.

### PR 5-2 최종 검증 — 2026-10-07

운영·테스트 컴파일과 전체 테스트 1,122개가 통과했다. 실패·오류·건너뜀은 0개다. 관련 테스트 144개 통과 후 설정 경계 테스트와 전체 실행 시 후보 데이터 간섭 보완을 포함해 다시 검증했다. 외부 AI 호출은 Stub으로 대체하고 실제 MySQL DB를 사용했다. 실제 AI 서버 계약 검증은 수행하지 않았다.
