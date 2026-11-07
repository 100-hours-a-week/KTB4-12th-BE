# 프로파일링 종료 작업 정리 — PR 4-2

관련 이슈: #526 / 상위 #522. PR 4-1의 수동 재개와 독립적인 작업 이력 정리다.

## 정책

- 실행: 매일 03:30 Asia/Seoul. 공통 SCHEDULING_ENABLED와 AI_PROFILE_CLEANUP_ENABLED가 모두 켜져 있어야 한다.
- COMPLETED는 finished_at이 실행 기준 7일보다 이전, FAILED는 30일보다 이전인 행만 삭제한다. 정확히 경계에 있는 행은 보존한다.
- PUBLISHED·published_at 존재·선점 없음·next_attempt_at 없음 조건을 함께 검사한다. WAITING·BLOCKED·SUPERSEDED·READY·CLAIMED·RETRY_WAIT와 불완전한 종료 기록은 보존한다.
- finished_at은 기존 BE Clock(Asia/Seoul) 기준으로 취급한다. 결과 Consumer가 구현될 때 같은 시간 기준을 사용해야 한다. 정리 작업이 종료 상태를 생성하지 않는다.
- 프로필·현재 추천 데이터는 수정하지 않는다. 새 테이블·컬럼·마이그레이션은 추가하지 않는다.

## 구현

ProfilingJobCleanupScheduler → ProfilingJobCleanupStore.deleteBatch 순서로 실행한다.

- 한 실행에서 고정한 기준 시각을 모든 배치가 공유한다.
- 한 배치 최대 1,000건, READ_COMMITTED·REQUIRES_NEW로 별도 커밋한다. 상태·시간·활성 여부를 DELETE WHERE에서 검사하고 finished_at·request_id 순서로 삭제한다.
- 기존 ShedLock과 shedlock 테이블을 재사용한다. 정리 잠금은 최대 1시간·최소 1분이다. 배치 크기와 잠금 시간은 초기 운영값이며 부하 측정으로 검증한 최적값이 아니다.
- 상태별로 실패를 격리한다. 실패한 배치는 롤백되고 앞서 커밋한 삭제는 유지된다. 로그는 삭제·커밋 수와 상태·기준 시각을 기록한다.
- AMQP와 AI health 상태는 정리 실행 조건이 아니다. 종료된 결과가 아직 없으면 삭제 0건이 정상이다.

## 설정

```dotenv
SCHEDULING_ENABLED=true
AI_PROFILE_CLEANUP_ENABLED=true
```

application.yaml은 AI_PROFILE_CLEANUP_ENABLED가 없으면 false를 사용한다. .env.example의 값은 실행 환경에 실제로 전달해야 한다.

## 검증

```bash
./gradlew :main-backend:test --tests '*ProfilingJobCleanup*Test'
```

- 스케줄러: 서울 03:30, 7일·30일 기준, 1,000건 반복, 상태별 실패 격리.
- 실제 MySQL: 마이크로초 시간 경계, 제외 상태·선점·재시도, 프로필·추천 보존, 배치 제한, 동시 갱신 후 삭제 조건 재확인.
- 최대 1,000건은 허용하고 1,001건 요청은 삭제 전에 거부한다.
- Repository 예외 변환에 맞춰 InvalidDataAccessApiUsageException과 원인 IllegalArgumentException을 함께 검증한다.

운영 DB 적용·실제 대량 삭제 부하는 별도 검증 대상이다.
