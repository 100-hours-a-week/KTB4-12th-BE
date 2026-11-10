# PR4 결과 Consumer 실제 장애·전체 회귀 검증

검증일: 2026-11-10. 아래 표는 PR3 베이스 `05ac0454`에 PR4 변경을 적용한 전체 실행의 결과다. 수정 전 실패 및 단독 재실행 결과와 합산하지 않는다.

PR 준비 과정에서 최신 develop `c84c06c1`을 반영했으며 해당 베이스의 전체 회귀·빌드는 별도로 재실행 중이다. 이전 베이스의 통과 건수를 최신 베이스 결과로 표시하지 않는다.

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

## 실제 장애 주입

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

실제 AI 연동, 운영 환경, 3AZ/다수 상실/네트워크 분할 failover(#599), 장기 장애·복구 부하 시험, 실배포 인스턴스 JMX 제어는 이번 실행으로 완료 처리하지 않는다. 결과 Consumer 기본 OFF를 유지하며 테스트 통과를 운영 활성화로 해석하지 않는다.

## 결과 파일

- [BE HTML 보고서](../main-backend/build/reports/tests/test/index.html)
- [결제 HTML 보고서](../payment-server/build/reports/tests/test/index.html)
- 각 모듈 `build/test-results/test/TEST-*.xml`에서 테스트별 결과를 확인할 수 있다. 생성된 보고서는 다음 테스트 실행 때 바뀔 수 있으므로 본 문서는 검증일의 확정 집계로 남긴다.
