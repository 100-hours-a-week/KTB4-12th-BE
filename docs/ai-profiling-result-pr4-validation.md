# PR4 결과 Consumer 실제 장애·전체 회귀 검증

검증일: 2026-11-10. 아래 표는 PR3 베이스 `05ac0454`에 PR4 변경을 적용한 전체 실행의 결과다. 수정 전 실패 및 단독 재실행 결과와 합산하지 않는다.

PR 준비 과정에서 최신 develop `c84c06c1`을 반영하고 전체 회귀·빌드를 별도로 재실행했다. 아래 최신 결과와 이전 베이스 결과를 구분하며 서로 합산하지 않는다.

## 최신 develop 기준 전체 회귀 결과

대상: develop `c84c06c1` + PR4 커밋 `76681306`. 로컬 전체 실행은 2026-11-10 09:54경 종료했고 두 모듈 build가 성공했다. 이후 테스트의 긴 대기를 줄이는 변경은 별도의 관련 테스트 검증으로 구분한다.

| 모듈 | 전체 | 통과 | 실패·오류 | 제외 |
| --- | ---: | ---: | ---: | ---: |
| main-backend | 2186 | 2177 | 0 | 9 |
| payment-server | 52 | 52 | 0 | 0 |

- 기존 Elasticsearch 환경 조건부 제외 9건이며 PR4 관련 시험을 새로 제외하지 않았다.
- PR4 관련 6개 클래스 115건에는 실제 DB·브로커 장애, 트랜잭션 관측, 상태 전이 검증이 포함된다.
- 실행 명령은 아래 전체 build 명령과 동일하다. 후속 실행으로 생성 보고서가 덮어써질 수 있으므로 본 표는 해당 전체 실행 종료 시 확인한 집계다.

## 이전 베이스 전체 검증 결과

| 모듈 | 전체 | 통과 | 실패·오류 | 제외 |
| --- | ---: | ---: | ---: | ---: |
| main-backend | 2162 | 2153 | 0 | 9 |
| payment-server | 52 | 52 | 0 | 0 |

- BE·결제 모듈 `build` 통과. 테스트 결과를 지우고 다시 실행했으며 테스트 태스크 캐시 결과를 완료로 간주하지 않았다.
- 제외 9건은 기존 `ProductSearchRepositoryIntegrationTest` 5건과 `ProductSearchSyncElasticsearchTest` 4건이다. 이번 변경에서 테스트를 새로 비활성화하지 않았다.
- 실제 RabbitMQ/MySQL 결과 Consumer 통합 17건과 결과 저장 통합 47건 모두 통과했다.
- `git diff --check` 통과. 별도 Formatter/Linter는 프로젝트에 구성된 검사로 추가 실행하지 않았다.

```bash
./gradlew :main-backend:cleanTest :payment-server:cleanTest :main-backend:build :payment-server:build --offline --continue
```

## 단축 전 실제 장애 주입 기록

운영 환경이 아닌 테스트 JVM 전용 MySQL 8.4 컨테이너와 테스트 RabbitMQ 컨테이너를 사용했다.

### MySQL 접속 차단·복구

`ProfilingResultBrokerIntegrationTest.recoversAfterActualDatabaseOutage()`에서 테스트 MySQL의 `offline_mode`를 실제 ON/OFF 했다. 장애 상태는 `finally`에서 해제한다.

- 실제 연결 실패가 일시 DB 오류로 분류되어 Consumer가 중지됨.
- 결과 메시지가 원본 큐에 남고 결과 DLQ로 이동하지 않음.
- 작업은 WAITING·기존 fingerprint 상태를 보존함.
- DB 접속 복구 후 같은 Consumer가 연속 정상 점검 2회를 거쳐 자동 재개함.
- 결과 COMPLETED·fingerprint 저장·추천 1건·결과 정산을 확인함.

이는 서버 프로세스 강제 종료 또는 네트워크 패킷 차단 시험이 아니라 실제 DB 접속 차단 시험이다.

### RabbitMQ 중단·재시작

`ProfilingResultBrokerIntegrationTest.recoversAfterActualBrokerRestart()`에서 테스트 브로커에 `rabbitmqctl stop_app`/`start_app`을 실행했다. `finally`에서 브로커 복구와 Management/Quorum 준비를 확인한다.

- 실제 서버의 연결 종료를 BROKER 장애로 분류함.
- 복구 후 같은 Consumer가 자동 재구독하고 결과를 한 번 반영함.
- 추천 1건·원본 결과 정산·결과 DLQ 미유입을 확인함.

## 발견·수정한 결함

최초 전체 회귀에서 RabbitMQ 실제 중단 시 `ShutdownSignalException`을 UNCLASSIFIED로 분류하여 자동 복구하지 못했다. `ProfilingResultListener.infrastructureReason()`에서 직접·중첩된 종료 신호를 확인하도록 수정했다.

- 서버가 보낸 `connection.close` 320(CONNECTION_FORCED)은 BROKER로 분류함.
- 접근 거부 403 등 구성 관련 종료는 자동 복구하지 않음.
- 직접·중첩 예외 4개 단위 검증을 추가함.
- 수정 후 실제 DB·Rabbit 장애 시험과 상태 전이 단독 실행 18건을 통과한 뒤 위 전체 회귀·빌드를 다시 실행함.

기존 PR3 통합 테스트의 저장 호출 1회 기대값은 PR4의 미분류 시도 상한 2회에 맞췄으며, 시작 실패 검증도 비동기 중지 상태를 확인하도록 변경했다. 안전 조건을 삭제하거나 실패 테스트를 제외하지 않았다.

## 함께 검증된 경계

- 커밋 전 오류는 DB 전체 롤백 및 커밋 관측자 미호출.
- 커밋 후 부수 효과 오류는 실제 커밋 결과를 관측자에 전달.
- 커밋 후 ACK 실패·동일 결과 재배달에서 중복 반영 방지.
- 두 Consumer의 prefetch·DB 멱등성, DLQ 대상 부재 중 보존과 복구 후 재전달.
- 구성·미분류 오류의 자동 재개 차단, 수동 중지 우선, 재시도·자동 재개 상한, 종료 후 재시작 차단.
- 기존 HTTP 콜백·Publisher·캐시·작업 정리 등 전체 회귀.

## 범위 제한

실제 AI 연동, 운영 환경, 3AZ/다수 상실/네트워크 분할 failover(#599), 장기 장애·복구 부하 시험, 실배포 인스턴스 JMX 제어는 후속 전환·운영 확인으로 둔다. 전체 요청→fixture 결과 연결 자동화도 전환 전 확인이며 PR4 코드 리뷰·병합을 이 항목들 때문에 대기시키지 않는다. 결과 Consumer 기본 OFF를 유지하며 테스트 통과를 운영 활성화로 해석하지 않는다.

## 결과 파일

- [BE HTML 보고서](../main-backend/build/reports/tests/test/index.html)
- [결제 HTML 보고서](../payment-server/build/reports/tests/test/index.html)
- 각 모듈 `build/test-results/test/TEST-*.xml`에서 테스트별 결과를 확인할 수 있다. 생성된 보고서는 다음 테스트 실행 때 바뀔 수 있으므로 본 문서는 검증일의 확정 집계로 남긴다.

## 테스트 대기 단축

운영 주기를 그대로 기다리는 검증을 반복하지 않도록 단위/통합의 책임을 분리했다. 단축 전 자동 재개 실시간 검증 기록은 위에 보존한다.

- 자동 재개 30초 주기·연속 정상 2회 정책: 시간 제어 단위 테스트.
- 실제 DB/RabbitMQ 통합: 장애 분류·미ACK 보존·복구 후 같은 Consumer 수동 재개·멱등 반영.
- DLQ: 테스트 컨테이너에서만 `dead_letter_worker_publisher_confirm_timeout=5000`을 사용하고 최대 20초 안에 최종 전달을 확인. 운영 기본값·TTL·delivery-limit·DLX 전달 보장은 그대로 둠.

설정 근거: [RabbitMQ 4.4 기본값](https://github.com/rabbitmq/rabbitmq-server/blob/v4.4.0/deps/rabbit/Makefile#L115), [DLX worker의 settle timeout 처리](https://github.com/rabbitmq/rabbitmq-server/blob/v4.4.0/deps/rabbit/src/rabbit_fifo_dlx_worker.erl).

### 단축 후 실행 결과

- PR4 관련 6개 클래스 115건: 실패·오류·제외 0건, 전체 명령 약 1분 59초.
- 중지 정리 완료 후 재개하도록 최종 시험 경합을 보완한 뒤 브로커 통합 17건 재실행: 실패·오류·제외 0건, 전체 명령 약 1분 54초(컨테이너/Spring 기동 포함).
- 마지막 브로커 재검증의 개별 동작 시간: MySQL 접속 차단/복구 약 7.5초, DLQ 보존/복구 약 6.4초, RabbitMQ 중단/복구 약 5.4초.
- 운영 코드 변경 없이 통합 테스트 대기와 검증 책임만 조정했다. 전체 회귀의 최신 베이스 집계와 단축 후 관련 재실행을 합산하지 않는다.
- 115건 보고서는 `/private/tmp/pr4-core-test-results/main-backend/`, 최종 17건 보고서는 `/private/tmp/pr4-core-final-test-results/main-backend/`에 분리했다. 기본 build 보고서 디렉터리는 다른 후속 테스트 실행으로 바뀔 수 있다.

```bash
./gradlew :main-backend:test --tests '*ProfilingResultFailureTest' --tests '*ProfilingResultMessageListenerTest' --tests '*ProfilingResultListenerTest' --tests '*ProfilingResultConsumerConfigurationTest' --tests '*ProfilingResultServiceIntegrationTest' --tests '*ProfilingResultBrokerIntegrationTest' --offline
```

실제 로컬 실행에서는 보고서 경로만 분리하는 임시 Gradle init script를 사용했다. 실행할 테스트나 제품 동작을 바꾸는 설정은 아니다. 위 명령은 동일한 테스트 선택을 재현한다.
