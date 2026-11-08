# 프로파일링 결과 계약 — PR1

관련 작업: [BE #581](https://github.com/100-hours-a-week/KTB4-12th-BE/issues/581),
[상위 BE #522](https://github.com/100-hours-a-week/KTB4-12th-BE/issues/522).
설계 기준: [BE Consumer](https://app.notion.com/p/4121885e7728806696a5d518fadebca7),
[AI 인계 계약](https://app.notion.com/p/4111885e772880b88264e5d86413ed1e).

## 구현 범위

본문 검증·불변 DTO·결과 동일성 해시·저장 컬럼만 제공한다.
Listener·결과 반영 트랜잭션·ACK·DLQ는 아직 연결하지 않는다. HTTP 콜백과 요청 발행 계약은 유지한다.

| 클래스 | 책임 |
| --- | --- |
| ProfilingResultMessageReader | byte[]를 단일 JSON 객체로 읽고 타입·필수값·상태별 구조 검증 |
| ProfilingResultMessage | 스키마 1·표준 UUID·Z로 끝나는 UTC Instant, 양수 수신자/버전, 성공/실패 분리 |
| ProfilingResultMessage.Data | 순서를 유지하는 중복 없는 양의 상품 ID 0~30개, 불변 목록 |
| ProfilingResultMessage.Error | 공백 아닌 code/message, 코드 허용 목록은 공동 조율 |
| ProfilingResultFingerprint | 업무 비교 필드를 고정 JSON으로 직렬화하여 SHA-256 계산 |
| ProfilingJob | NULL 허용 resultFingerprint 매핑 추가 |

Reader 전용 Mapper는 중복 JSON 키와 추가 JSON 본문을 거부한다.
문자열·소수·bool을 정수로 변환하지 않으며 schemaVersion 1의 알 수 없는 필드는 무시한다.
공통 HTTP Mapper는 변경하지 않는다. AMQP 속성 및 작업/수신자/버전의 DB 대응 검증은 후속 구현이다.

본문 예제: [성공](../main-backend/src/test/resources/profiling/result-succeeded.json),
[빈 추천](../main-backend/src/test/resources/profiling/result-empty.json),
[실패](../main-backend/src/test/resources/profiling/result-failed.json).
ANALYSIS_FAILED는 예시 코드다. createdAt은 최초 결과 생성 시각으로 재발행 시 유지하며 Reader는 형식만 검증한다.

## fingerprint 입력 규칙

성공: `{"outcome":"SUCCEEDED","recommendedProductIds":[103,208]}`

실패: `{"outcome":"FAILED","code":"ANALYSIS_FAILED"}`

필드 순서·공백 없는 JSON·UTF-8·추천 배열 순서를 고정하고 소문자 SHA-256 hex 64자리로 표현한다.
createdAt·error.message·요청 식별 필드는 제외하며 상품 존재 여부를 필터링하기 전 원본 결과를 사용한다.
입력 규칙은 저장된 해시와의 호환성을 고려해 유지한다. AI 메시지에 fingerprint를 추가하지 않는다.

requestId는 작업 식별·멱등 처리의 기준이며 fingerprint는 동일 작업의 중복/상충 결과 구분용이다.
해시만으로 중복 저장을 방지하지 않는다. 작업 잠금·종료 상태 검사·원자적 반영은 후속 PR 책임이다.

## ERD 변경 — V53

| 테이블 | 컬럼 | 타입 | NULL | 제약 |
| --- | --- | --- | --- | --- |
| profiling_jobs | result_fingerprint | CHAR(64) | 허용 | NULL 또는 소문자 hex 64자리 CHECK |

컬럼과 형식 CHECK만 추가한다. 기존 행·추천·상태는 보존하며 backfill하지 않는다.
서로 다른 작업의 해시가 같을 수 있어 UNIQUE·검색 인덱스는 추가하지 않는다.
종료 상태와 fingerprint NOT NULL을 묶는 제약도 추가하지 않는다.
기존 COMPLETED/FAILED·NULL 재수신의 격리는 BD-02 및 후속 Consumer 구현을 따른다.
이 문서는 코드/테스트 기준이며 운영 DB 적용이나 Notion ERD 변경을 의미하지 않는다.

## 검증

```bash
./gradlew :main-backend:test --tests '*ProfilingResult*' --tests '*ProfilingJobPreparationIntegrationTest'
```

DTO 2건·Reader 45건·fingerprint 7건·MySQL 마이그레이션 1건으로 신규 55건을 검증한다.
MySQL 테스트는 기존 프로필/추천/모든 작업 상태 보존·NULL·중복 해시 허용·형식 CHECK를 확인한다.
기존 작업 준비 테스트의 INSERT에는 대상 컬럼을 명시해 컬럼 추가 후에도 UNIQUE 충돌/롤백을 검증한다.
