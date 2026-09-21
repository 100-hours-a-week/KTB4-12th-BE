# AGENTS.md

이 문서는 이 저장소에서 작업하는 모든 AI Coding Agent가 따라야 하는 공통 지침이다.

목표는 기존 설계와 컨벤션을 유지하면서 요청된 범위만 안전하게 변경하는 것이다.

---

# 1. 기본 작업 원칙

작업 전 반드시 현재 저장소의 실제 구조와 관련 코드를 먼저 확인한다.

판단 우선순위는 다음과 같다.

1. 현재 Repository의 실제 코드
2. ERD / API 명세 / Tech Spec
3. 프로젝트 컨벤션
4. 기존 테스트 코드
5. README 및 기타 프로젝트 문서
6. 사용자의 현재 요청

문서와 실제 구현이 충돌하면 임의로 추측하여 수정하지 않는다.

코드에 존재하지 않는 클래스, 필드, API, 테이블, 설정을 실제로 존재하는 것처럼 가정하지 않는다.

불확실한 내용은 다음과 같이 구분한다.

- **확인된 사실**: 코드 또는 문서에서 직접 확인
- **추론**: 현재 구현을 근거로 판단
- **제안**: 개선을 위한 권장사항

---

# 2. 최소 변경 원칙

사용자가 요청한 기능을 구현하기 위해 필요한 범위만 수정한다.

다음을 임의로 수행하지 않는다.

- 관련 없는 리팩터링
- 패키지 구조 변경
- 클래스 및 변수의 대규모 이름 변경
- 요청하지 않은 기능 추가
- 미래 확장을 위한 추상화
- 불필요한 Interface 또는 계층 추가
- 기존 패턴을 다른 아키텍처로 변경
- 다른 팀원이 작성한 코드를 현재 작업에 맞춰 임의 수정

기존 구현으로 해결할 수 있다면 가장 단순한 방법을 선택한다.

---

# 3. 새로운 기술 도입

다음과 같은 기술을 단순히 확장성 또는 Best Practice라는 이유만으로 추가하지 않는다.

- Redis
- Kafka
- RabbitMQ
- Message Broker
- Distributed Lock
- Outbox Pattern
- CQRS
- Saga
- 새로운 Cache
- 새로운 외부 Library
- 새로운 Infrastructure

도입이 필요한 경우 반드시 먼저 다음을 확인한다.

```text
현재 어떤 문제가 있는가
→ 기존 기술로 해결할 수 없는가
→ 새 기술이 문제를 어떻게 해결하는가
→ 추가되는 복잡성과 비용은 무엇인가
```

실제 문제가 없다면 도입하지 않는다.

---

# 4. 패키지 구조

패키지는 계층 전체를 묶지 않고 **도메인 기준**으로 구성한다.

```text
com.example.project
├── global
│   ├── config
│   ├── exception
│   ├── security
│   └── common
│
├── user
│   ├── controller
│   ├── service
│   ├── repository
│   ├── entity
│   └── dto
│
└── gift
    ├── controller
    ├── service
    ├── repository
    ├── entity
    └── dto
```

`global`에는 여러 도메인에서 공통으로 사용하는 코드만 둔다.

특정 도메인에서만 사용하는 코드를 `global`로 이동하지 않는다.

---

# 5. Layer 책임

기본 호출 구조는 다음을 따른다.

```text
Controller
    ↓
Service
    ↓
Repository
    ↓
Database
```

## Controller

Controller는 다음만 담당한다.

- HTTP 요청 수신
- Request DTO 변환
- 입력값 형식 검증
- Service 호출
- HTTP 응답 반환

Controller에서 다음을 하지 않는다.

- Repository 직접 호출
- DB 접근
- 비즈니스 규칙 처리
- Entity 직접 응답
- 개별 `try-catch`를 통한 공통 예외 처리

## Service

Service는 하나의 기능에 필요한 비즈니스 흐름을 담당한다.

가능한 책임:

- 여러 Repository 조합
- 비즈니스 상태 검증
- Entity 상태 변경
- 트랜잭션 관리
- 비즈니스 예외 발생

Service는 HTTP Request/Response 객체에 의존하지 않는다.

## Repository

Repository는 데이터 접근만 담당한다.

가능:

```text
findById
findByUserId
existsBy...
save
delete
```

다음과 같은 비즈니스 행위를 Repository에 구현하지 않는다.

```text
sendGift
cancelGift
validateGift
```

---

# 6. 클래스 네이밍

역할을 이름으로 명확하게 표현한다.

```text
UserController
UserService
UserRepository

GiftController
GiftService
GiftRepository
```

DTO도 역할을 이름에 포함한다.

```text
CreateGiftRequest
UpdateGiftRequest
GiftResponse
GiftDetailResponse
```

다음처럼 의미가 불명확한 이름은 사용하지 않는다.

```text
GiftDto
GiftData
GiftInfo
GiftObject
```

---

# 7. DTO / Entity 규칙

Request와 Response DTO를 분리한다.

하나의 DTO를 Request와 Response에 동시에 사용하지 않는다.

Controller에서 Entity를 직접 반환하지 않는다.

```java
// 금지
return giftRepository.findById(id).get();
```

Response DTO로 변환한다.

```java
return GiftResponse.from(gift);
```

Response DTO는 `from()` 정적 팩토리 메서드로 생성한다.

```java
public record GiftResponse(
    Long giftId,
    GiftStatus status
) {
    public static GiftResponse from(Gift gift) {
        return new GiftResponse(
            gift.getId(),
            gift.getStatus()
        );
    }
}
```

Controller나 Service에서 Response DTO의 생성자를 직접 호출하여 변환 로직을 분산하지 않는다.

Entity 구조와 API 응답 구조가 직접 결합되지 않도록 한다.

---

# 8. Entity 규칙

Entity는 DB 영속성 모델이며 API 모델로 사용하지 않는다.

무분별한 public setter 사용을 피한다.

```java
// 지양
gift.setStatus(status);
```

상태 변경에 의미가 있는 경우 행위를 표현하는 메서드를 사용한다.

```java
gift.cancel();
gift.complete();
```

Entity 생성은 프로젝트의 기존 생성 방식을 따른다.

현재 기본 방식:

```java
new Gift(sender, receiver, product);
```

기존 코드와 다른 생성 패턴을 임의로 추가하지 않는다.

---

# 9. API URL

일반적인 CRUD API는 리소스를 명사로 표현한다.

```text
GET    /users/{userId}
POST   /gifts
GET    /gifts/{giftId}
PATCH  /gifts/{giftId}
DELETE /gifts/{giftId}
```

일반 CRUD에 다음과 같은 URL을 사용하지 않는다.

```text
/getGift
/createGift
/updateGift
/deleteGift
```

CRUD로 표현하기 어려운 명확한 비즈니스 행위는 예외적으로 동사를 사용할 수 있다.

```text
POST /gifts/{giftId}/cancel
POST /orders/{orderId}/confirm
```

---

# 10. HTTP Method

기본 기준:

| 목적 | Method |
|---|---|
| 조회 | GET |
| 생성 | POST |
| 전체 교체 | PUT |
| 일부 변경 | PATCH |
| 삭제 | DELETE |

특별한 이유가 없다면 수정 API는 `PATCH`를 기본으로 한다.

---

# 11. HTTP Status Code

기본 기준:

| 상황 | Status |
|---|---|
| 정상 조회/수정 | 200 OK |
| 정상 생성 | 201 Created |
| 정상 처리, 반환 데이터 없음 | 204 No Content |
| 잘못된 요청 | 400 Bad Request |
| 인증 실패 | 401 Unauthorized |
| 권한 없음 | 403 Forbidden |
| 리소스 없음 | 404 Not Found |
| 상태 충돌 / 중복 | 409 Conflict |
| 서버 오류 | 500 Internal Server Error |

비즈니스 상태 충돌을 전부 `400 Bad Request`로 처리하지 않는다.

현재 상태와 요청이 충돌하는 경우 `409 Conflict` 사용을 검토한다.

---

# 12. API 응답

프로젝트의 기존 공통 응답 형식을 유지한다.

성공 예시:

```json
{
  "message": "회원가입에 성공했습니다.",
  "data": {
    "userId": 1
  }
}
```

데이터가 없는 성공 응답:

```json
{
  "message": "삭제했습니다.",
  "data": {}
}
```

실패 예시:

```json
{
  "message": "입력값을 확인해 주세요.",
  "error": {
    "code": "INVALID_REQUEST",
    "traceId": "01JXYZ8D7G5K2M4N6P8Q",
    "details": [
      {
        "field": "email",
        "reason": "INVALID_FORMAT"
      }
    ]
  }
}
```

새 API를 추가할 때 기존 응답 구조를 임의로 변경하지 않는다.

---

# 13. Validation

DTO Validation과 비즈니스 Validation을 구분한다.

## Request DTO

형식 자체의 유효성은 Request DTO에서 검증한다.

```java
@NotBlank
private String name;

@Positive
private Integer quantity;
```

예:

- null 여부
- 문자열 길이
- 숫자 범위
- 이메일 형식

## Service

현재 시스템 상태를 확인해야 하는 규칙은 Service에서 검증한다.

예:

- 재고가 충분한가
- 이미 참여했는가
- 현재 취소 가능한 상태인가

```text
"수량이 1 이상인가?"
→ DTO Validation

"주문 수량만큼 현재 재고가 존재하는가?"
→ Business Validation
```

---

# 14. Repository Query

Spring Data JPA 메서드 이름은 의미가 명확해야 한다.

```java
findByUserId(...)
existsByUserIdAndProductId(...)
findAllByStatus(...)
```

메서드 이름이 지나치게 길고 복잡해지면 QueryDSL 또는 명시적인 Query 사용을 검토한다.

QueryDSL은 단순 조회에도 무조건 사용하지 않는다.

---

# 15. Optional

Repository에서 존재하지 않을 수 있는 단건 조회는 `Optional` 사용을 허용한다.

```java
Optional<User> findByEmail(String email);
```

Service에서 필요한 비즈니스 예외로 변환한다.

```java
User user = userRepository.findById(userId)
    .orElseThrow(UserNotFoundException::new);
```

`Optional`을 Controller까지 전달하지 않는다.

Entity 필드 타입으로 `Optional`을 사용하지 않는다.

---

# 16. Transaction

트랜잭션은 기본적으로 Service 계층에서 관리한다.

```java
@Transactional
public void sendGift(...) {
    // ...
}
```

조회 전용 로직은 필요한 경우 다음을 사용한다.

```java
@Transactional(readOnly = true)
```

Controller에 `@Transactional`을 추가하지 않는다.

하나의 기능에서 모두 성공하거나 모두 실패해야 하는 DB 변경은 동일 트랜잭션으로 처리한다.

예:

```text
선물 생성
+
재고 차감
+
선물 이력 생성
```

외부 API처럼 오래 걸릴 수 있는 작업을 무조건 DB Transaction 안에 포함하지 않는다.

---

# 17. 데이터 정합성

데이터 변경 기능을 구현할 때 다음을 반드시 검토한다.

- 중복 요청
- 동시 요청
- Race Condition
- 트랜잭션 중간 실패
- 부분 성공
- 현재 상태와 요청 상태의 충돌
- DB Constraint 필요 여부

애플리케이션의 선조회만으로 동시성 안전성이 보장된다고 가정하지 않는다.

```java
if (!repository.exists(...)) {
    repository.save(...);
}
```

위 패턴이 중복 생성을 반드시 막아야 하는 경우 DB `UNIQUE` 등의 제약도 함께 검토한다.

---

# 18. DB Constraint

애플리케이션 검증만으로 반드시 유지되어야 하는 데이터 규칙을 보장하지 않는다.

필요에 따라 다음 제약조건을 사용한다.

```text
NOT NULL
UNIQUE
FOREIGN KEY
CHECK
```

예:

```text
이메일 중복 불가
→ UNIQUE

선물은 반드시 사용자와 연결
→ FOREIGN KEY + NOT NULL
```

DB Schema 변경이 필요한 경우 프로젝트가 사용하는 Migration 방식을 확인하고 함께 수정한다.

---

# 19. 동시성

동시성 문제 해결을 위해 무조건 Lock을 사용하지 않는다.

먼저 실제 경쟁 상태와 깨지는 데이터 규칙을 확인한다.

해결책은 문제에 따라 선택한다.

- DB UNIQUE
- Conditional UPDATE
- Pessimistic Lock
- Optimistic Lock
- Idempotency
- Transaction Isolation

선택 시 다음 근거가 있어야 한다.

```text
어떤 Race Condition이 발생하는가
→ 어떤 데이터가 잘못되는가
→ 선택한 방법이 이를 어떻게 방지하는가
```

---

# 20. Exception

Controller마다 `try-catch`를 작성하지 않는다.

공통 예외 처리는 다음 구조를 사용한다.

```text
global
└── exception
    ├── GlobalExceptionHandler
    ├── ErrorCode
    └── BusinessException
```

비즈니스 예외는 의미를 이름으로 표현한다.

```text
UserNotFoundException
GiftNotFoundException
OutOfStockException
DuplicateGiftException
```

예상 가능한 비즈니스 실패와 시스템 오류를 구분한다.

## 비즈니스 실패

- 재고 없음
- 중복 요청
- 존재하지 않는 사용자
- 허용되지 않는 상태 변경

## 시스템 오류

- DB 연결 실패
- 예상하지 못한 NullPointerException
- 외부 시스템 장애

프로젝트의 기존 Exception 구조가 있다면 이를 재사용한다.

---

# 21. Lombok

Entity에 `@Data`를 사용하지 않는다.

Entity에 무분별한 `@Setter`를 사용하지 않는다.

필요한 기능만 명시적으로 사용한다.

```java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
```

Lombok 사용으로 Entity의 상태 변경 지점이 불명확해지지 않도록 한다.

---

# 22. Logging

`System.out.println()`을 사용하지 않는다.

SLF4J를 사용한다.

```java
@Slf4j
```

기본 기준:

```text
INFO
→ 정상적인 주요 흐름

WARN
→ 처리는 가능하지만 확인할 필요가 있는 상황

ERROR
→ 정상적으로 처리하지 못한 오류
```

다음 정보를 로그로 남기지 않는다.

- Password
- Access Token
- Refresh Token
- Secret Key
- 개인정보
- 기타 인증 정보

---

# 23. Test

변경한 기능에 영향을 받는 테스트를 반드시 확인한다.

Service의 비즈니스 규칙은 최소한 다음을 고려한다.

- 정상 처리
- 존재하지 않는 데이터
- 중복 요청
- 허용되지 않는 상태
- 경계값

복잡한 Repository Query는 실제 DB 동작을 기준으로 검증한다.

다음은 H2 결과만으로 운영 DB와 동일하다고 판단하지 않는다.

- DB Lock
- Isolation Level
- DB Constraint
- DB-specific SQL
- 동시성

필요하면 운영 DB와 동일한 DB를 Testcontainers로 실행한다.

---

# 24. Test Naming

테스트 메서드는 영어로 작성한다.

테스트 의미는 `@DisplayName`을 사용하여 한글로 명확하게 작성한다.

```java
@Test
@DisplayName("상품 조회 시 품절된 상품이면 예외를 발생시킨다")
void sendGift_fails_whenProductIsOutOfStock() {
    // ...
}
```

테스트 이름만 보아도 조건과 예상 결과를 이해할 수 있도록 한다.

---

# 25. 기존 테스트 보호

새로운 구현 때문에 기존 테스트가 실패하더라도 테스트를 임의로 삭제하거나 기대값을 변경하지 않는다.

먼저 다음을 확인한다.

```text
기존 테스트가 잘못되었는가
새로운 구현이 잘못되었는가
기능 요구사항 자체가 변경되었는가
```

기존 테스트의 의미를 확인한 후 필요한 경우에만 수정한다.

---

# 26. 날짜와 시간

새로운 코드에서는 Java Time API를 사용한다.

기본 사용 타입:

```text
LocalDate
LocalDateTime
```

새 코드에서 다음 타입을 사용하지 않는다.

```text
Date
Calendar
```

Time Zone 정책이 필요한 기능은 기존 서버/DB/API 정책을 먼저 확인한다.

---

# 27. Enum

가능한 값이 제한되는 상태는 Enum 사용을 우선 검토한다.

```java
public enum GiftStatus {
    PENDING,
    COMPLETED,
    CANCELLED
}
```

JPA Entity에 저장할 경우 기본적으로 문자열 저장을 사용한다.

```java
@Enumerated(EnumType.STRING)
```

`EnumType.ORDINAL`을 사용하지 않는다.

---

# 28. Null

`null`에 불명확한 비즈니스 의미를 부여하지 않는다.

```java
// 지양
status = null;
```

가능하면 명확한 상태를 표현한다.

DB에서 반드시 존재해야 하는 데이터는 `NOT NULL` 제약을 함께 검토한다.

---

# 29. 코드 포맷

Java 코드는 **공백 4칸**으로 들여쓴다.

Tab 문자를 사용하지 않는다.

```java
if (user.isActive()) {
    giftService.sendGift(user);
}
```

## 중괄호

여는 중괄호는 선언문 또는 제어문의 같은 줄에 작성한다.

```java
public void sendGift() {
    if (condition) {
        // ...
    }
}
```

다음 형식은 사용하지 않는다.

```java
public void sendGift()
{
}
```

## 줄 길이

한 줄은 가능한 한 **120자 이내**로 유지한다.

긴 Parameter나 메서드 호출은 의미 단위로 줄바꿈한다.

```java
Gift gift = giftService.createGift(
    senderId,
    receiverId,
    productId
);
```

단순한 코드까지 불필요하게 여러 줄로 나누지 않는다.

## 빈 줄

서로 다른 의미의 코드 블록 사이에는 빈 줄 하나를 사용한다.

연속된 여러 개의 빈 줄을 만들지 않는다.

---

# 30. Import

동일 패키지에서 일반 클래스를 5개 이상 사용하거나 동일 클래스에서 static 멤버를 3개 이상 사용하는 경우
Wildcard Import를 허용한다.

```java
// 허용
import java.util.*;
import static org.assertj.core.api.Assertions.*;
```

Import는 Formatter 또는 IDE 기준으로 정렬한다.

기본 그룹:

```text
java.*

javax.* / jakarta.*

외부 Library

프로젝트 내부 Package

static import
```

사용하지 않는 Import는 반드시 제거한다.

코드 변경 과정에서 추가했다가 사용하지 않게 된 Import도 정리한다.

---

# 31. 수동 코드 정렬 금지

공백을 이용하여 변수 선언을 수동 정렬하지 않는다.

```java
// 금지
String name     = "gift";
Long   userId   = 1L;
int    quantity = 3;
```

다음과 같이 작성한다.

```java
String name = "gift";
Long userId = 1L;
int quantity = 3;
```

현재 작업과 관련 없는 주변 코드의 Formatting까지 변경하지 않는다.

---

# 32. Formatter

프로젝트에 Formatter 설정이 존재하면 해당 설정을 최우선으로 따른다.

Spotless가 설정되어 있다면 다음 명령을 사용한다.

검사:

```bash
./gradlew spotlessCheck
```

수정:

```bash
./gradlew spotlessApply
```

Formatter가 정한 결과와 개별 스타일이 충돌하면 Formatter 결과를 따른다.

프로젝트에 Spotless가 없다면 사용자의 요청 없이 임의로 추가하지 않는다.

---

# 33. Comment

코드를 그대로 읽으면 알 수 있는 내용을 주석으로 작성하지 않는다.

```java
// 사용자를 조회한다.
User user = userRepository.findById(id);
```

주석이 필요한 경우 **무엇을 하는지보다 왜 이렇게 구현했는지**를 설명한다.

```java
// 동시 요청에서도 중복 생성을 막기 위해 DB UNIQUE 제약과 함께 검증한다.
```

코드 변경으로 기존 주석이 틀리게 되었다면 함께 수정하거나 제거한다.

---

# 34. Dependency

새로운 Dependency를 추가하기 전에 기존 Dependency로 해결 가능한지 확인한다.

추가해야 하는 경우 다음을 명확히 한다.

```text
어떤 Dependency를 추가하는가
왜 필요한가
현재 기술로 해결할 수 없는 이유는 무엇인가
```

단순 편의를 위해 새로운 Library를 추가하지 않는다.

---

# 35. 보안

다음 정보를 코드에 직접 작성하지 않는다.

- Password
- JWT Secret
- API Key
- Access Token
- AWS Credential
- DB Password

프로젝트가 현재 사용 중인 환경변수 또는 Secret 관리 방식을 따른다.

새로운 `.env` 또는 Secret 관리 방식을 임의로 추가하지 않는다.

환경변수가 추가될 경우 `.env.example`에도 함께 반영한다.

`.env.example`에는 실제 값이나 Secret을 작성하지 않고 변수 이름과 예시 형식만 작성한다.

---

# 36. Issue

Issue 내용은 한글로 작성한다.

## 작업 관리 단위

기본 작업 관리 구조:

```text
구현 Issue 1개
    ↓
작업 Branch 1개
    ↓
의미 있는 작업 단위의 여러 Commit
    ↓
Pull Request 1개
```

여기서 구현 Issue는 실제 코드를 변경하는 저장소에 생성된 가장 세부적인 작업 Issue를 의미한다.

Issue는 독립적으로 구현하고 검증할 수 있는 작업 단위로 작성한다.

Entity, Repository, Service, Controller 등 기술 계층만을 기준으로 Issue를 분리하지 않는다.

하나의 기능에 필요한 구현, Test, 관련 문서 수정은 같은 Issue와 PR에 포함한다.

서로 관련 없는 작업은 별도 Issue와 PR로 분리한다.

## Issue 제목

제목 형식:

```text
[PREFIX] 작업 내용
```

대괄호 안에는 도메인명이 아닌 작업 유형을 작성한다.

Prefix는 대문자로 작성한다.

```text
FEAT
FIX
REFACTOR
TEST
DOCS
CHORE
```

적용 기준:

- FEAT: 새로운 기능 추가
- FIX: 버그 수정
- REFACTOR: 기존 동작을 유지하는 코드 구조 개선
- TEST: 테스트 추가 또는 개선이 주목적인 작업
- DOCS: 문서 추가 또는 수정
- CHORE: 빌드, Dependency, 개발 도구 등 설정 변경

예:

```text
[FEAT] 회원 및 약관 도메인 모델 구현
[FIX] 중복 회원가입 예외 처리 수정
[REFACTOR] 회원 조회 로직 개선
[TEST] 약관 동의 제약조건 테스트 추가
[DOCS] 공통 API 응답 규칙 수정
[CHORE] Gradle 설정 변경
```

기능 구현에 필요한 테스트를 함께 추가하는 경우에는 FEAT Issue에 포함한다.
테스트가 포함되어 있다는 이유만으로 별도 TEST Issue를 생성하지 않는다.

## Issue Template

다음 Template을 기본으로 사용한다.

```markdown
## 목표

- 이 작업이 필요한 이유와 구현할 결과를 작성한다.

## 구현 범위

### 포함

- 이번 Issue에서 구현할 내용을 작성한다.

### 제외

- 관련 기능 중 이번 Issue에서 구현하지 않을 내용을 작성한다.
- 제외할 내용이 없다면 "없음"으로 작성한다.

## 상세 작업

- [ ] 상세 작업 1
- [ ] 상세 작업 2
- [ ] 관련 테스트 작성
- [ ] 설계 변경 시 관련 문서 수정

## 완료 조건

- 구현 결과를 확인할 수 있는 조건을 작성한다.
- 정상 동작과 주요 예외 상황의 검증 기준을 작성한다.
- 필요한 테스트의 통과 조건을 작성한다.

## 의존성 및 참고 문서

- 선행 Issue: #이슈번호 또는 없음
- API Specification:
- ERD:
- Tech Spec:
```

상세 작업은 구현해야 할 일을 작성한다.

완료 조건은 작업 결과가 요구사항을 충족했는지 확인할 수 있는 기준을 작성한다.

민감한 정보나 실제 Secret을 Issue에 작성하지 않는다.

## Issue 메타데이터

Issue를 생성할 때 본문만 작성하지 않고 다음 메타데이터도 함께 설정하고, 생성 후 실제 반영 여부를 확인한다.

- **Project**: 이 저장소에서 생성하는 모든 Issue를 Organization Project인 `KTB4-12th-project`에 등록한다.
    - Project의 Auto-add 규칙으로 이미 등록되었다면 중복으로 추가하지 않고 등록 상태만 확인한다.
    - Auto-add 대상이 아니거나 자동 등록되지 않았다면 `KTB4-12th-project`에 수동으로 추가한다.
- **Project Status**: `KTB4-12th-project`에 등록한 Issue의 Status는 `Backend-Issue`로 설정한다.
    - Auto-add로 등록되면서 다른 Status가 지정되었다면 `Backend-Issue`로 변경한다.
- **Type**: Issue 제목의 Prefix와 작업 목적에 맞는 GitHub Issue Type을 설정한다.
    - `FEAT` → `Feature`
    - `FIX` → `Bug`
    - `REFACTOR`, `TEST`, `DOCS`, `CHORE` → `Task`
    - 저장소 또는 Organization에서 실제로 제공하는 Type 이름이 위 이름과 다르면 제공되는 Type을 기준으로 대응한다.
    - 대응 관계가 불명확하거나 필요한 Type이 없으면 임의로 생성하거나 선택하지 않고 사용자에게 알린다.
- **Assignee**: 사용자가 담당자를 지정하지 않았다면 Issue를 생성하는 현재 인증 GitHub 사용자(`@me`)를 할당한다.
    - 사용자가 담당자를 명시했다면 해당 사용자를 우선한다.
    - 요청 없이 다른 팀원을 담당자로 지정하지 않는다.

GitHub CLI로 Project를 설정하려면 `project` 권한이 필요하다. 권한 부족으로 Project 또는 Type을 조회·설정할 수
없으면 Issue 생성 자체를 실패로 처리하지 말고, 설정하지 못한 메타데이터와 필요한 권한을 완료 보고에 명시한다.

## Issue 완료

작업 Branch의 구현을 완료한 뒤 develop을 대상으로 PR을 생성한다.

PR이 develop에 병합되고 완료 조건을 충족했는지 확인한 후 Issue를 종료한다.

상세 작업 체크리스트를 모두 완료했다는 이유만으로,
리뷰와 병합 전에 Issue를 종료하지 않는다.

사용자가 명시적으로 요청하지 않는 한 Agent가 임의로
GitHub Issue를 생성, 수정 또는 종료하지 않는다.

---

# 37. Git Branch

작업 Branch 명명 형식:

```text
prefix/#이슈번호-기능
```

Prefix는 작업 유형에 맞게 소문자로 작성한다.
대괄호는 실제 Branch 이름에 포함하지 않으며, 이슈번호 앞의 `#`은 포함한다.

기능명은 영문 소문자로 작성하고 단어는 하이픈으로 구분한다.

기본 Branch 구조:

```text
main
develop
feature/*
fix/*
```

예:

```text
feature/#12-gift-create
feature/#13-user-login
fix/#14-gift-duplicate
```

기존 Branch 전략이 존재하면 기존 전략을 우선한다.

여러 저장소가 참여하는 기능에서는 현재 코드 저장소의 구현 Issue 번호를 Branch 이름에 사용한다.
Wiki의 상위 기능 Issue 번호를 대신 사용하지 않는다.

---

# 38. Commit

Commit Message는 한글로 작성한다.

Prefix:

```text
feat
fix
refactor
test
docs
chore
```

예:

```text
feat: 선물 생성 API 구현
fix: 중복 선물 생성 문제 수정
refactor: 선물 조회 로직 리팩터링
test: 선물 생성 서비스 테스트 추가
docs: API 문서 수정
chore: Gradle 설정 변경
```

사용자가 명시적으로 요청하지 않는 한 Agent가 임의로 Commit하지 않는다.

---

# 39. Pull Request

PR 내용은 한글로 작성한다.

## PR Base Branch

- PR의 Base Branch는 반드시 `develop`으로 지정한다.
- 사용자가 현재 요청에서 명시적으로 `main`을 Base Branch로 지정해 달라고 요청한 경우에만 `main`을 사용한다.
- 최종 배포, 릴리스, 기본 Branch 병합 등의 상황을 Agent가 임의로 추론해 `main`을 선택하지 않는다.

## 여러 저장소의 Issue 계층

여러 저장소가 함께 구현하는 기능은 다음 구조로 관리한다.

```text
KTB4-12th-wiki #106  받은 선물 목록 기능
├── KTB4-12th-BE #24  받은 선물 목록 API 구현
└── KTB4-12th-FE #31  받은 선물 목록 화면 구현
```

- `KTB4-12th-wiki` Issue는 기능 명세, 사용자 시나리오, 전체 완료 조건을 관리하는 상위 Issue로 사용한다.
- `KTB4-12th-BE` Issue는 독립적으로 구현하고 검증할 수 있는 백엔드 작업 단위로 작성한다.
- `KTB4-12th-FE` Issue는 독립적으로 구현하고 검증할 수 있는 프론트엔드 작업 단위로 작성한다.
- BE와 FE의 구현 Issue는 해당 Wiki 기능 Issue의 하위 Issue로 연결한다.
- 상위 Issue와 하위 Issue에 동일한 구현 체크리스트를 중복해서 작성하지 않는다.

작업과 PR을 준비할 때 다음 순서로 Issue를 확인한다.

1. Wiki Issue 목록에서 현재 변경사항이 속한 상위 기능 Issue를 찾는다.
2. 현재 코드 저장소에서 해당 기능의 가장 세부적인 구현 Issue를 찾는다.
3. 구현 Issue가 Wiki 기능 Issue의 하위 Issue로 연결되어 있는지 확인한다.
4. Issue 제목만으로 판단하지 않고 본문, 구현 범위, 완료 조건을 실제 변경사항과 대조한다.

Wiki Issue 목록:

```text
https://github.com/100-hours-a-week/KTB4-12th-wiki/issues
```

적합한 구현 Issue가 없거나 부모·하위 관계가 불명확하면 임의로 다른 Issue를 사용하지 않는다.
사용자에게 필요한 Issue와 연결 관계를 알리고 확인한다. 사용자가 명시적으로 요청하지 않는 한 Agent가
Issue를 생성하거나 기존 Issue의 부모·하위 관계를 변경하지 않는다.

## PR의 Issue 참조

PR의 `관련 이슈 또는 문서` 항목에는 현재 저장소의 세부 구현 Issue와 Wiki의 상위 기능 Issue를 모두
작성한다.

```text
Refs #24
Refs 100-hours-a-week/KTB4-12th-wiki#106
```

현재 저장소의 구현 Issue는 `#이슈번호`로 작성할 수 있다. 다른 저장소의 Wiki Issue는 반드시
`100-hours-a-week/KTB4-12th-wiki#이슈번호`처럼 저장소 전체 경로를 작성한다.

PR이 서로 다른 여러 구현 Issue의 범위를 포함한다면 먼저 PR 분리가 가능한지 검토한다. 하나의 기능 흐름이라
분리하기 어렵다면 관련 구현 Issue를 각각 작성하고 공통 상위 Wiki Issue도 함께 작성한다.

`develop` 대상 기능 PR에는 기본적으로 `Refs`를 사용한다. 기본 Branch에 병합되는 최종 PR에서 구현 Issue를
자동 종료해야 한다면 `Closes #구현이슈번호`를 사용한다. Wiki 상위 Issue는 BE와 FE를 포함한 모든 하위 Issue의
완료 조건을 충족한 뒤 종료하며, 하나의 구현 PR에서 바로 종료하지 않는다.

PR에는 최소한 다음 내용을 포함한다.

```text
PR 유형

변경 사항

관련 이슈 또는 문서

특별히 봐줬으면 하는 부분

테스트 가이드

기타

체크리스트
```

하나의 PR에 서로 관련 없는 여러 기능을 포함하지 않는다.

## PR Assignee

PR을 생성할 때 Assignee도 함께 지정하고, 생성 후 실제 반영 여부를 확인한다.

- 사용자가 담당자를 지정하지 않았다면 PR을 생성하는 현재 인증 GitHub 사용자(`@me`)를 할당한다.
- 사용자가 담당자를 명시했다면 해당 사용자를 우선한다.
- Reviewer와 Assignee는 서로 다른 역할이므로, Reviewer 지정만으로 Assignee 설정을 대신하지 않는다.
- 요청 없이 다른 팀원을 Assignee로 지정하지 않는다.

---

# 40. Git 안전 규칙

사용자의 명시적인 요청 없이는 다음 작업을 수행하지 않는다.

```text
git commit
git push
git merge
git rebase
git reset --hard
git clean
branch 삭제
force push
```

사용자가 이미 수정한 코드를 임의로 되돌리지 않는다.

현재 작업과 관계없는 변경사항을 삭제하거나 덮어쓰지 않는다.

---

# 41. 문서 동기화

코드 변경으로 실제 설계가 달라지는 경우 관련 문서도 확인한다.

대상:

- ERD
- API Specification
- Tech Spec
- README

단순 내부 구현 변경까지 모든 문서를 수정하지 않는다.

코드와 문서가 서로 다른 상태로 남는 경우만 필요한 문서를 함께 수정한다.

Controller를 작성한 경우에는 위 기준과 별개로 `docs/postman`의 컬렉션과 환경 파일도 함께 업데이트한다.

---

# 42. 작업 완료 전 검증

작업 완료를 보고하기 전에 가능한 범위에서 반드시 검증한다.

기본 순서:

```text
1. Compile
2. 관련 Test
3. 전체 Test
4. Formatter / Linter
```

Gradle 프로젝트라면 기존 프로젝트 설정을 먼저 확인한다.

일반적인 검증 예:

```bash
./gradlew test
```

Spotless가 존재한다면:

```bash
./gradlew spotlessCheck
```

실행하지 않은 테스트를 실행했다고 보고하지 않는다.

실패한 테스트가 있다면 숨기지 않고 실패 내용과 현재 작업과의 관련성을 설명한다.

---

# 43. 작업 완료 보고

코드 변경 후 결과는 다음 형식으로 간결하게 보고한다.

```text
변경 파일
- GiftService.java
- GiftRepository.java
- GiftServiceTest.java

변경 이유
- 동일 사용자의 중복 선물 생성을 방지하기 위해 수정

핵심 변경
- 중복 검증 추가
- DB 제약조건 추가
- 관련 예외 처리 추가

검증
- GiftServiceTest 통과
- 전체 테스트 통과
- spotlessCheck 통과

추가 확인 필요
- 없음
```

실제로 수행하지 않은 작업을 완료했다고 작성하지 않는다.

---

# 44. 최종 판단 우선순위

구현 방법이 여러 개인 경우 다음 순서로 판단한다.

1. 데이터 정합성
2. 기존 기능 안정성
3. 현재 기능 요구사항 충족
4. 기존 프로젝트 구조와 일관성
5. 구현 단순성
6. 성능
7. 미래 확장성

미래 확장성만을 이유로 현재 구현을 복잡하게 만들지 않는다.

**실제 서비스에서 발생 가능한 문제인지 확인한 후 필요한 수준까지만 해결한다.**
