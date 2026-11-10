# PR4 — 프로파일링 결과 Consumer 장애 복구·운영 제어

관련 작업: [BE #584](https://github.com/100-hours-a-week/KTB4-12th-BE/issues/584), [상위 #522](https://github.com/100-hours-a-week/KTB4-12th-BE/issues/522). 선행 PR3: [#592](https://github.com/100-hours-a-week/KTB4-12th-BE/pull/592).

## 목적과 변경 전후

DB·브로커 장애 중 결과를 보존하고, 오류 종류와 운영자의 결정을 확인한 뒤 안전하게 소비를 재개한다.

| 항목 | PR3 | PR4 |
| --- | --- | --- |
| 저장 오류 | 한 번 실패하면 중지 | 원인을 분류하고 제한 재시도 후 중지 |
| 복구 | 중지한 객체의 재시작 차단 | 일시 장애의 자동 재개 및 검증 후 수동 재개 |
| 중지 상태 | boolean | 실행 상태·중지 원인·정상 점검 횟수·세대 번호 |
| 운영 제어 | 별도 JVM의 BLOCKED DB 작업 복구 도구 | 실행 JVM의 Listener 상태 조회·중지·재개 |
| 커밋 이후 예외 | 저장 실패와 구분하기 어려움 | 실제 커밋 관측 결과를 기준으로 재저장 여부 결정 |
| 관측 | 기존 요청 발행 중심 | 결과 처리·재시도·복구·결과 대기 관측 추가 |

## 신규 클래스 4개

| 클래스 | 역할 | 핵심 동작·추가 이유 |
| --- | --- | --- |
| `ProfilingResultFailure` | 저장 오류 분류 | `classify()`가 예외 원인·SQLState·MySQL 코드를 확인한다. 연결·잠금 오류와 인증·권한·SQL 오류를 구분하여 모든 DataAccessException을 자동 재개하지 않는다. |
| `ProfilingResultControlMBean` | 운영 제어 계약 | 인스턴스 ID·상태 조회와 대상 ID를 명시한 중지·재개만 공개한다. |
| `ProfilingResultControl` | 실제 JVM과 운영 도구 연결 | 실행 중인 Listener를 플랫폼 MBeanServer에 등록한다. 대상 ID를 확인하고 종료 시 등록을 해제한다. 기존 DB 복구 명령과 역할을 분리한다. |
| `ProfilingResultBacklog` | 결과 대기 관측 | `PUBLISHED + WAITING` 작업 수·가장 오래된 대기 시간·조회 성공 여부·측정값 나이를 관측한다. 조회 실패를 대기 0건으로 표시하지 않는다. |

## 기존 클래스 4개 변경

| 클래스 | 변경 내용 | 이유 |
| --- | --- | --- |
| `ProfilingResultMessageListener` | 검증·저장 재시도·ACK를 분리하고 대기 취소 및 커밋 후 오류 처리를 추가 | ACK 실패나 지표 오류 때문에 저장을 반복하지 않으며, 잠금을 보유한 채 재시도 대기하지 않음 |
| `ProfilingResultListener` | 상태·중지 원인·복구 점검·자동/수동 재개·종료 관리 | 수동 중지를 자동 점검이 덮어쓰거나 종료 후 Consumer가 다시 켜지는 경합 방지 |
| `ProfilingResultConsumerConfiguration` | 복구 실행기·DB/토폴로지 점검·MeterRegistry·JMX·대기 지표 연결 | 기존 공통 ConnectionFactory와 수동 ACK 설정을 재사용하고 재시작 책임을 상태 관리자 한 곳에 둠 |
| `ProfilingResultService` | `saveWithCommitObserver()` 추가 | 기존 `save()` 업무 로직을 유지하면서 실제 커밋 여부를 Listener에 별도로 전달 |

## 메시지 처리 흐름

1. 본문과 AMQP `message_id`·`correlation_id`·`requestId`의 대응을 검증한다.
2. `saveWithCommitObserver()`를 Spring 프록시로 호출하여 새 READ_COMMITTED 트랜잭션에서 기존 저장 로직을 수행한다.
3. 저장 실패는 오류 종류에 따라 제한 재시도한다. 이전 트랜잭션이 끝난 후 대기하고 다음 시도에서 다시 검증한다.
4. 커밋이 확인되면 저장 결과에 따라 해당 메시지만 ACK 또는 Reject한다.
5. 중지 시 추가 저장을 차단하고 Consumer·채널 정리를 요청한다. 미ACK 메시지는 브로커 재배달 대상이다.

| 상황 | 처리 |
| --- | --- |
| 잘못된 본문·AMQP 식별자 또는 QUARANTINE | `Reject(requeue=false)`로 격리 |
| 정상적인 AI FAILED 결과 | 업무 실패를 DB에 기록하고 ACK |
| 일시 DB 오류 | 최초 포함 3회, 1초·2초 대기 후 미ACK 중지 |
| 구성 오류 | 재시도·자동 재개 없이 중지 |
| 미분류 저장 오류 | 별도 설정 상한 소진 후 중지·수동 조사 |
| 실제 커밋 확인 후 부수 효과 오류 | 관측한 저장 결론으로 정산하고 재저장하지 않음 |
| ACK/Reject 전송 오류 | 저장 반복문으로 돌아가지 않고 중지·재배달 처리 |

지연만으로 요청을 재발행하거나 작업을 FAILED로 바꾸지 않는다. 작업 상태·버전·fingerprint를 강제로 초기화하지 않는다.

## 복구 상태와 경합 방지

- 상태는 `STOPPED / STARTING / RUNNING / PAUSED / CLOSED`로 구분한다. PAUSED의 중지 원인을 별도로 관리한다.
- 일시 DB·브로커·정산 통신 장애만 자동 점검 대상이다. 수동·구성·미분류·중지 실패·자동 재개 상한 도달은 자동 재개하지 않는다.
- 약 30초±5초 지터로 점검하고 연속 정상 2회 후 재개한다. 점검 실패 시 정상 횟수를 초기화한다.
- 단일 복구 실행기와 예약 작업 관리로 점검의 중복 실행을 막는다.
- 상태 변경 시 세대 번호를 바꾼다. 점검 중 수동 중지·종료가 발생하면 오래된 점검 성공 결과를 폐기한다.
- 중지 콜백으로 Consumer 정리가 확인된 뒤에만 복구 점검을 예약한다.
- 재시도 대기는 수동 중지·종료 시 깨운다. 종료 시 예약 작업을 취소하고 복구 실행기를 종료한다.
- 복구 점검의 DB 읽기 성공은 실제 쓰기·잠금·커밋 성공을 보장하지 않는다. 실제 처리의 제한 재시도·재중지 경로를 유지한다.

### 설정값

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| `app.ai-profile.result-consumer.enabled` | `false` | 결과 소비 기본 OFF |
| `app.ai-profile.result-consumer.unclassified-max-attempts` | `2` | 미분류 저장 오류의 최초 포함 최대 시도 수, 허용 범위 1~10 |
| `app.ai-profile.result-consumer.automatic-resume-limit` | `3` | 저장 결과의 정산 성공 없이 반복되는 자동 재개의 상한, 허용 범위 1~10 |
| `app.instance-id` | JVM 런타임 이름 | 운영 제어 대상 식별. 미지정 시 일반적으로 PID@host |

자동 재개 횟수는 저장 결과의 ACK/Reject 성공 또는 수동 재개 시 초기화한다. 이 값은 인스턴스 메모리의 예산이며 브로커 전체의 delivery-limit 예산을 보장하지 않는다.

## 운영 제어 인터페이스

ObjectName: `com.gift.gift:type=ProfilingResultConsumer,instance="<인스턴스 ID>"`

| 기능 | 용도 |
| --- | --- |
| `getInstanceId()` | 실제 연결한 JVM의 대상 ID 확인 |
| `getStatus()` | 상태·중지 원인·변경 시각·정상 점검 횟수·정리 여부·자동 재개 횟수·컨테이너 실행 여부 조회 |
| `pause(expectedInstanceId)` | 해당 인스턴스 수동 중지 |
| `resume(expectedInstanceId)` | 대상 ID와 정리 상태 확인 후 재검증·재개 요청 |

운영 전제는 동일 OS 사용자 권한으로 대상 JVM에 로컬 JMX 연결하는 방식이다. 이 클래스는 원격 JMX 서버를 생성하지 않으며, 원격 JMX 포트 시스템 속성이 설정되어 있으면 등록을 거부한다. 별도 코드·런타임에서 원격 JMX를 노출하지 않아야 한다. 대상 ID 확인은 인증을 대신하지 않는다.

### 최소 운영 순서

1. 대상 서버/컨테이너와 JVM PID를 식별한 후 로컬 JMX로 연결한다. 로컬 attach가 불가능한 배포 환경은 제어 연결을 별도로 마련하고 검증해야 한다.
2. `getInstanceId()`와 `getStatus()`로 대상을 확인한다. 여러 인스턴스의 전체 중지는 각 JVM을 지정해 수행한다.
3. 중지할 때 `pause(ID)`를 호출하고 `PAUSED / MANUAL`, `stopping=false`, `consumerRunning=false`를 확인한다.
4. 원인을 수정한 뒤 `resume(ID)`를 호출한다. 반환은 재개 요청 수락이며 실제 재개 완료가 아니므로 `RUNNING`과 `consumerRunning=true`를 다시 조회한다.
5. `STOP_FAILURE`처럼 정리가 끝나지 않은 상태에서는 원인을 조사한다. 작업 버전·중지 플래그를 강제 초기화하지 않는다.

기존 `ProfilingRecoveryCommand`는 BLOCKED DB 작업 복구용이므로 위 Listener 제어에 사용하지 않는다. 결과 Consumer OFF 설정은 재시작/재배포 시 적용되며 HTTP fallback을 의미하지 않는다. 이미 발행된 요청·결과·종료 이력을 삭제하지 않는다.

DLQ 조사에서는 원본 메시지와 식별자·격리 원인·현재 DB 상태를 대조하고 원인을 수정한 뒤 재투입 대상을 선별한다. 이 PR에 결과 DLQ의 일괄 재발행 도구는 추가하지 않는다.

## 최소 관측

- `profiling.result.delivery`: 처리 결론·재시도·ACK/Reject·커밋 후 오류.
- `profiling.result.processing`: 처리 시간.
- `profiling.result.recovery`: 중지 원인·복구 점검·자동/수동 재개.
- `profiling.result.consumer.running`: 소비 실행 여부.
- `profiling.result.waiting.*`: 발행 후 결과 대기 수·최장 대기·샘플 성공 여부와 나이.

requestId는 로그 추적에 사용하며 지표 라벨로 사용하지 않는다. 알림 발송·경보 채널·대시보드 확장은 최후속 작업이다.

## 추가·변경한 테스트

아래 건수는 PR4 관련 클래스 전체의 파라미터 확장 후 실행 건수이며, 전부 신규 테스트라는 의미는 아니다.

| 테스트 | 구분 | 건수 | 주요 검증 |
| --- | --- | ---: | --- |
| `ProfilingResultFailureTest` | 신규 | 6 | 연결·deadlock·lock timeout·감싸진 권한 오류·SQL 오류·미분류 |
| `ProfilingResultMessageListenerTest` | 기존 보완 | 26 | 저장 후 정산 유지, DB 3회/1초·2초 대기, 미분류 상한, 구성 무재시도, 커밋 후 오류·대기 취소 |
| `ProfilingResultListenerTest` | 기존 보완 | 16 | 정상 2회 조건, 실패 시 초기화, 수동 중지 경합, 자동 재개 상한, 종료 취소, 브로커 종료 코드 |
| `ProfilingResultConsumerConfigurationTest` | 생성자 호출 보완 | 3 | 기본 OFF·AMQP 전제·공통 연결과 수동 ACK·동시성/prefetch 유지 |
| `ProfilingResultServiceIntegrationTest` | 기존 보완 | 47 | 실제 트랜잭션의 커밋 전 롤백·커밋 후 오류와 관측자 전달, 기존 저장·경합 회귀 |
| `ProfilingResultBrokerIntegrationTest` | 기존 보완 | 17 | 실제 DB 접속 차단·브로커 중단/복구, 기존 재배달·DLQ·두 Consumer 경합 |
| 합계 | 신규·기존 포함 | 115 | PR4 영향 범위 |

`MySqlTestContainerInitializer.setOffline()`는 테스트 전용 MySQL 접속 차단/해제를 위한 보조 코드다. 운영 DB에 접근하는 도구가 아니다. 복구 단위 테스트는 실제 30초 대기를 사용하지 않고 예약 작업을 직접 진행시킨다.

### 실제 장애 시험과 발견한 결함

- MySQL `offline_mode ON/OFF`: 미ACK·WAITING·fingerprint 보존, 같은 Consumer 자동 재개, 추천 1회 반영을 확인했다. 서버 강제 종료·패킷 차단 시험과 구분한다.
- RabbitMQ `stop_app/start_app`: 실제 연결 종료와 서버 복구 후 같은 Consumer 재구독·결과 반영을 확인했다.
- 최초 실행에서 서버의 `ShutdownSignalException`을 미분류 오류로 판단해 복구가 막혔다. 직접·중첩된 `connection.close` 320은 브로커 장애로, 403 등은 구성 오류로 분리하고 재검증했다.
- 커밋 전 예외에서는 롤백·관측자 미호출, 커밋 후 예외에서는 실제 커밋 결론 전달을 확인했다.

## 검증 기록과 남은 완료 조건

상세 실행 결과는 [PR4 실제 장애·전체 회귀 검증](ai-profiling-result-pr4-validation.md)에 기록한다. 이전 PR3 베이스의 전체 회귀는 BE 2,153건·결제 52건 통과, 기존 Elasticsearch 조건부 제외 9건이었다. 최신 develop 반영 후의 재실행 결과는 별도 기록하며 서로 합산하지 않는다.

현재 코드는 구현·로컬 검증 결과이며 다음을 완료로 표시하지 않는다.

- 실제 BE 요청 발행 → fixture 결과 발행 → 결과 소비의 전체 자동화 및 CI 실행 결과 확인. 기존 CI는 MySQL/RabbitMQ Testcontainers 시험과 XML/HTML artifact 업로드를 포함하며 이번 PR에서 워크플로를 새로 만들지 않는다.
- 3AZ·다수 상실·네트워크 분할·다중 인스턴스 복구 부하·장기 중지와 delivery-limit/ACK timeout의 종합 검증: #599 및 관련 완료 조건.
- 실배포 JVM의 로컬 JMX 연결·권한·인스턴스 지정 검증.
- 실제 AI 메시지 계약·저장 결과 재발행·전환 환경과 운영 활성화: 상위 #522.

결과 소비 기본 OFF를 유지한다. 구현, PR 리뷰/병합, 실제 AI 연동, 운영 활성화는 각각 구분해 기록한다.
