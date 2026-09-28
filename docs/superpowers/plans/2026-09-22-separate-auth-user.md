# 회원·인증 도메인 패키지 분리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 회원가입과 회원정보 기능은 `domain.user`에 유지하고 로그인·세션·토큰·로그인 제한 기능을 `domain.auth`로 분리한다.

**Architecture:** `auth`가 인증 흐름을 소유하고 필요한 회원 정보는 `user`의 공개 Entity·Repository·조회 Service를 사용한다. `user`는 `auth`에 의존하지 않으며 JWT와 Spring Security 공통 기반은 `global.security`에 유지한다. 파일 이동과 import 변경만 수행하고 외부 동작은 보존한다.

**Tech Stack:** Java 25, Spring Boot, Spring Security, Spring Data JPA, JUnit 5, AssertJ, Gradle

**Spec:** `docs/superpowers/specs/2026-09-22-separate-auth-user-design.md`

## Global Constraints

- 작업 브랜치는 `refactor/#140-separate-auth-user`다.
- 회원가입·약관·프로필·온보딩은 `domain.user`에 유지한다.
- 로그인·로그아웃·토큰 재발급·인증 세션·로그인 제한·인증 데이터 정리만 `domain.auth`로 이동한다.
- Endpoint, JSON, Cookie, HTTP 상태, 오류 코드, 메시지, 트랜잭션과 DB 매핑은 변경하지 않는다.
- 새로운 의존성을 추가하지 않는다.
- 이동은 `git mv`를 사용하고 package/import 외의 코드 변경을 피한다.
- 각 단계는 관련 테스트가 통과하는 상태로 커밋한다.

## Review Focus

- `auth`의 Service가 이동 후에도 `user`의 `User`, `UserRepository`, `EmailNormalizer`를 올바르게 참조하는지 확인한다.
- `GlobalExceptionHandler`, `WebMvcConfig`, `LoginRateLimitConfig`가 이동된 auth 타입을 참조하는지 확인한다.
- JPA Entity와 Repository 이동 후 테이블 매핑과 비관적 락 테스트가 그대로 동작하는지 확인한다.
- 로그인 Controller 이동 후 Security 공개 URL, Interceptor 경로와 Cookie 응답이 변하지 않는지 확인한다.
- 전체 소스와 테스트에서 이전 auth 클래스의 `domain.user` 참조가 남지 않는지 확인한다.

---

### Task 1: 인증 영속성 모델과 지원 코드 분리

**Files:**
- Create: `src/test/java/com/gift/gift/domain/architecture/AuthUserPackageStructureTest.java`
- Move: `src/main/java/com/gift/gift/domain/user/entity/UserSession.java` → `src/main/java/com/gift/gift/domain/auth/entity/UserSession.java`
- Move: `src/main/java/com/gift/gift/domain/user/entity/LoginIpRateLimit.java` → `src/main/java/com/gift/gift/domain/auth/entity/LoginIpRateLimit.java`
- Move: `src/main/java/com/gift/gift/domain/user/entity/LoginEmailFailureLimit.java` → `src/main/java/com/gift/gift/domain/auth/entity/LoginEmailFailureLimit.java`
- Move: `src/main/java/com/gift/gift/domain/user/repository/UserSessionRepository.java` → `src/main/java/com/gift/gift/domain/auth/repository/UserSessionRepository.java`
- Move: `src/main/java/com/gift/gift/domain/user/repository/LoginIpRateLimitRepository.java` → `src/main/java/com/gift/gift/domain/auth/repository/LoginIpRateLimitRepository.java`
- Move: `src/main/java/com/gift/gift/domain/user/repository/LoginEmailFailureLimitRepository.java` → `src/main/java/com/gift/gift/domain/auth/repository/LoginEmailFailureLimitRepository.java`
- Move: `src/main/java/com/gift/gift/domain/user/support/LoginResult.java` → `src/main/java/com/gift/gift/domain/auth/support/LoginResult.java`
- Move: `src/main/java/com/gift/gift/domain/user/support/TokenRefreshResult.java` → `src/main/java/com/gift/gift/domain/auth/support/TokenRefreshResult.java`
- Move: `src/main/java/com/gift/gift/domain/user/support/LoginRateLimitDecision.java` → `src/main/java/com/gift/gift/domain/auth/support/LoginRateLimitDecision.java`
- Move: `src/main/java/com/gift/gift/domain/user/support/LoginRateLimitProperties.java` → `src/main/java/com/gift/gift/domain/auth/support/LoginRateLimitProperties.java`
- Move: `src/main/java/com/gift/gift/domain/user/support/RateLimitIdentifierHasher.java` → `src/main/java/com/gift/gift/domain/auth/support/RateLimitIdentifierHasher.java`
- Modify: 이동 타입을 사용하는 운영 코드와 테스트의 import

**Interfaces:**
- Consumes: `domain.user.entity.User`, `domain.user.repository.UserRepository`
- Produces: `domain.auth.entity.*`, `domain.auth.repository.*`, `domain.auth.support.*`

- [ ] **Step 1: 영속성·지원 타입의 목표 패키지를 검증하는 실패 테스트 작성**

```java
package com.gift.gift.domain.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class AuthUserPackageStructureTest {

    @Test
    @DisplayName("인증 영속성 모델과 지원 타입은 auth 도메인에 위치한다")
    void authPersistenceAndSupportTypesBelongToAuthDomain() {
        assertPresent("com.gift.gift.domain.auth.entity.UserSession");
        assertPresent("com.gift.gift.domain.auth.entity.LoginIpRateLimit");
        assertPresent("com.gift.gift.domain.auth.entity.LoginEmailFailureLimit");
        assertPresent("com.gift.gift.domain.auth.repository.UserSessionRepository");
        assertPresent("com.gift.gift.domain.auth.repository.LoginIpRateLimitRepository");
        assertPresent("com.gift.gift.domain.auth.repository.LoginEmailFailureLimitRepository");
        assertPresent("com.gift.gift.domain.auth.support.LoginResult");
        assertPresent("com.gift.gift.domain.auth.support.TokenRefreshResult");
        assertPresent("com.gift.gift.domain.auth.support.LoginRateLimitDecision");
        assertPresent("com.gift.gift.domain.auth.support.LoginRateLimitProperties");
        assertPresent("com.gift.gift.domain.auth.support.RateLimitIdentifierHasher");
    }

    private void assertPresent(String className) {
        assertThatCode(() -> Class.forName(className))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 구조 테스트가 새 auth 클래스 부재로 실패하는지 확인**

Run:

```bash
./gradlew test --tests '*AuthUserPackageStructureTest'
```

Expected: `ClassNotFoundException: com.gift.gift.domain.auth.entity.UserSession`으로 FAIL

- [ ] **Step 3: Entity·Repository·Support 파일 이동과 package/import 수정**

각 파일의 package를 `com.gift.gift.domain.auth.entity`, `repository`, `support`로 변경한다. 이동 타입을 사용하는 Controller, Service, Scheduler, Config와 테스트는 새 import를 사용한다. `UserSession`이 참조하는 `User`는 `com.gift.gift.domain.user.entity.User`를 유지한다.

- [ ] **Step 4: 구조 테스트와 인증 영속성 테스트 실행**

```bash
./gradlew test \
  --tests '*AuthUserPackageStructureTest' \
  --tests '*UserSessionTest' \
  --tests '*LoginIpRateLimitTest' \
  --tests '*LoginEmailFailureLimitTest' \
  --tests '*LoginRateLimitRepositoryTest' \
  --tests '*UserSessionPersistenceTest'
```

Expected: PASS

- [ ] **Step 5: 첫 번째 구현 커밋**

```bash
git add src/main/java src/test/java
git commit -m "refactor: 인증 영속성 모델과 지원 코드 분리"
```

---

### Task 2: 인증 애플리케이션과 웹 계층 분리

**Files:**
- Move: `src/main/java/com/gift/gift/domain/user/controller/LoginController.java` → `src/main/java/com/gift/gift/domain/auth/controller/LoginController.java`
- Move: `src/main/java/com/gift/gift/domain/user/controller/AuthSessionController.java` → `src/main/java/com/gift/gift/domain/auth/controller/AuthSessionController.java`
- Move: `src/main/java/com/gift/gift/domain/user/dto/request/LoginRequest.java` → `src/main/java/com/gift/gift/domain/auth/dto/request/LoginRequest.java`
- Move: `src/main/java/com/gift/gift/domain/user/dto/response/LoginResponse.java` → `src/main/java/com/gift/gift/domain/auth/dto/response/LoginResponse.java`
- Move: `src/main/java/com/gift/gift/domain/user/dto/response/LoginUserResponse.java` → `src/main/java/com/gift/gift/domain/auth/dto/response/LoginUserResponse.java`
- Move: `src/main/java/com/gift/gift/domain/user/dto/response/TokenRefreshResponse.java` → `src/main/java/com/gift/gift/domain/auth/dto/response/TokenRefreshResponse.java`
- Move: `src/main/java/com/gift/gift/domain/user/service/LoginService.java` → `src/main/java/com/gift/gift/domain/auth/service/LoginService.java`
- Move: `src/main/java/com/gift/gift/domain/user/service/LoginSessionService.java` → `src/main/java/com/gift/gift/domain/auth/service/LoginSessionService.java`
- Move: `src/main/java/com/gift/gift/domain/user/service/LogoutService.java` → `src/main/java/com/gift/gift/domain/auth/service/LogoutService.java`
- Move: `src/main/java/com/gift/gift/domain/user/service/TokenRefreshService.java` → `src/main/java/com/gift/gift/domain/auth/service/TokenRefreshService.java`
- Move: `src/main/java/com/gift/gift/domain/user/service/LoginRateLimiter.java` → `src/main/java/com/gift/gift/domain/auth/service/LoginRateLimiter.java`
- Move: `src/main/java/com/gift/gift/domain/user/service/LoginRateLimitTransactionService.java` → `src/main/java/com/gift/gift/domain/auth/service/LoginRateLimitTransactionService.java`
- Move: `src/main/java/com/gift/gift/domain/user/service/AuthenticationDataCleanupTransactionService.java` → `src/main/java/com/gift/gift/domain/auth/service/AuthenticationDataCleanupTransactionService.java`
- Move: `src/main/java/com/gift/gift/domain/user/exception/LoginRateLimitExceededException.java` → `src/main/java/com/gift/gift/domain/auth/exception/LoginRateLimitExceededException.java`
- Move: `src/main/java/com/gift/gift/domain/user/scheduler/AuthenticationDataCleanupScheduler.java` → `src/main/java/com/gift/gift/domain/auth/scheduler/AuthenticationDataCleanupScheduler.java`
- Move: `src/main/java/com/gift/gift/domain/user/web/LoginIpRateLimitInterceptor.java` → `src/main/java/com/gift/gift/domain/auth/web/LoginIpRateLimitInterceptor.java`
- Modify: `src/main/java/com/gift/gift/global/exception/GlobalExceptionHandler.java`
- Modify: `src/main/java/com/gift/gift/global/config/WebMvcConfig.java`
- Modify: `src/main/java/com/gift/gift/global/config/LoginRateLimitConfig.java`
- Modify: `src/test/java/com/gift/gift/domain/architecture/AuthUserPackageStructureTest.java`
- Modify: 이동 타입을 사용하는 전체 운영 코드와 테스트 import

**Interfaces:**
- Consumes: Task 1의 auth Entity·Repository·Support, user의 `UserRepository`, `User`, `UserStatus`, `EmailNormalizer`
- Produces: `/auth/login`, `/auth/refresh`, `/auth/logout`의 기존 동작을 유지하는 `domain.auth` 애플리케이션 계층

- [ ] **Step 1: 애플리케이션·웹 타입의 목표 패키지 검증을 구조 테스트에 추가**

```java
@Test
@DisplayName("로그인과 인증 세션 애플리케이션 타입은 auth 도메인에 위치한다")
void authApplicationTypesBelongToAuthDomain() {
    assertPresent("com.gift.gift.domain.auth.controller.LoginController");
    assertPresent("com.gift.gift.domain.auth.controller.AuthSessionController");
    assertPresent("com.gift.gift.domain.auth.service.LoginService");
    assertPresent("com.gift.gift.domain.auth.service.LoginSessionService");
    assertPresent("com.gift.gift.domain.auth.service.LogoutService");
    assertPresent("com.gift.gift.domain.auth.service.TokenRefreshService");
    assertPresent("com.gift.gift.domain.auth.service.LoginRateLimiter");
    assertPresent("com.gift.gift.domain.auth.scheduler.AuthenticationDataCleanupScheduler");
    assertPresent("com.gift.gift.domain.auth.web.LoginIpRateLimitInterceptor");
}
```

- [ ] **Step 2: 확장한 구조 테스트가 새 Controller 부재로 실패하는지 확인**

```bash
./gradlew test --tests '*AuthUserPackageStructureTest'
```

Expected: `ClassNotFoundException: com.gift.gift.domain.auth.controller.LoginController`로 FAIL

- [ ] **Step 3: 인증 Controller·DTO·Service·Exception·Scheduler·Web 이동**

package와 import를 새 `domain.auth` 경로로 변경한다. 인증 Service가 필요한 `User`, `UserStatus`, `UserRepository`, `EmailNormalizer`는 `domain.user`에서 import한다. URL 매핑과 메서드 본문은 변경하지 않는다.

- [ ] **Step 4: 전역 설정과 공통 예외 처리기의 import 수정**

```text
GlobalExceptionHandler → auth.exception.LoginRateLimitExceededException
WebMvcConfig → auth.web.LoginIpRateLimitInterceptor
LoginRateLimitConfig → auth.support.LoginRateLimitProperties
```

- [ ] **Step 5: 구조·Controller·Service 관련 테스트 실행**

```bash
./gradlew test \
  --tests '*AuthUserPackageStructureTest' \
  --tests '*LoginControllerTest' \
  --tests '*AuthSessionControllerTest' \
  --tests '*LoginServiceTest' \
  --tests '*LoginSessionServiceTest' \
  --tests '*LogoutServiceTest' \
  --tests '*TokenRefreshServiceTest' \
  --tests '*LoginRateLimiterTest' \
  --tests '*LoginIpRateLimitInterceptorTest' \
  --tests '*AuthenticationDataCleanupTransactionServiceTest' \
  --tests '*AuthenticationDataCleanupSchedulerTest'
```

Expected: PASS

- [ ] **Step 6: 두 번째 구현 커밋**

```bash
git add src/main/java src/test/java
git commit -m "refactor: 인증 애플리케이션과 웹 계층 분리"
```

---

### Task 3: 인증 테스트 패키지 정리와 회원 기능 회귀 검증

**Files:**
- Move: 인증 대상 `src/test/java/com/gift/gift/domain/user/**` → 대응하는 `src/test/java/com/gift/gift/domain/auth/**`
- Keep: 회원가입·약관·회원정보·온보딩 테스트는 `domain.user`에 유지
- Modify: 이동 테스트의 package와 import

**Interfaces:**
- Consumes: Task 1·2에서 확정한 운영 코드 패키지
- Produces: 운영 코드와 같은 경계를 따르는 auth 테스트 패키지

- [ ] **Step 1: 인증 테스트 파일을 대응하는 auth 패키지로 이동**

이동 대상:

```text
controller/AuthSessionControllerTest.java
controller/LoginControllerTest.java
dto/request/LoginRequestTest.java
entity/UserSessionTest.java
entity/LoginIpRateLimitTest.java
entity/LoginEmailFailureLimitTest.java
repository/AuthenticationDataCleanupRepository.java
repository/LoginRateLimitRepositoryTest.java
repository/UserSessionPersistenceTest.java
scheduler/AuthenticationDataCleanupSchedulerTest.java
service/AuthenticationDataCleanupTransactionServiceTest.java
service/LoginRateLimiterTest.java
service/LoginServiceTest.java
service/LoginSessionServiceTest.java
service/LogoutServiceTest.java
service/TokenRefreshServiceTest.java
support/RateLimitIdentifierHasherTest.java
web/LoginIpRateLimitInterceptorTest.java
```

- [ ] **Step 2: 회원가입·회원정보·온보딩 테스트가 user에 남았는지 확인**

```bash
find src/test/java/com/gift/gift/domain/user -type f | sort
```

Expected: Signup, Term, User, UserProfile, Onboarding 관련 테스트만 출력

- [ ] **Step 3: 이전 패키지 참조가 남지 않았는지 검사**

```bash
rg 'com\.gift\.gift\.domain\.user\.(entity\.(UserSession|LoginIpRateLimit|LoginEmailFailureLimit)|repository\.(UserSessionRepository|LoginIpRateLimitRepository|LoginEmailFailureLimitRepository)|service\.(LoginService|LoginSessionService|LogoutService|TokenRefreshService|LoginRateLimiter)|support\.(LoginResult|TokenRefreshResult|LoginRateLimitDecision|LoginRateLimitProperties|RateLimitIdentifierHasher)|exception\.LoginRateLimitExceededException|web\.LoginIpRateLimitInterceptor)' src/main/java src/test/java
```

Expected: 출력 없음

- [ ] **Step 4: 인증과 회원 관련 전체 테스트 실행**

```bash
./gradlew test --tests 'com.gift.gift.domain.auth.*' --tests 'com.gift.gift.domain.user.*'
```

Expected: PASS

- [ ] **Step 5: 테스트 패키지 정리 커밋**

```bash
git add src/test/java
git commit -m "test: 인증 도메인 테스트 패키지 분리"
```

---

### Task 4: 문서 동기화와 전체 검증

**Files:**
- Modify: `../docs/auth-user_domain_tech_spec.md`
- Verify: `docs/superpowers/specs/2026-09-22-separate-auth-user-design.md`

**Interfaces:**
- Consumes: 실제로 완료된 auth/user 패키지 구조
- Produces: 구현과 일치하는 회원·인증 테크 스펙 및 검증 결과

- [ ] **Step 1: 테크 스펙의 패키지 구조와 책임 설명 수정**

로그인·세션·토큰·로그인 제한·인증 데이터 정리 코드는 `domain.auth`, 회원가입·약관·회원정보·온보딩 코드는 `domain.user`가 담당한다는 내용을 반영한다. API 계약과 ERD는 변경하지 않는다.

- [ ] **Step 2: 컴파일 실행**

```bash
./gradlew compileJava compileTestJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 전체 테스트 실행**

환경 변수가 필요한 경우 프로젝트의 기존 `.env` 로딩 방식을 사용한다.

```bash
set -a
source .env
set +a
./gradlew test
```

Expected: 모든 테스트 PASS

- [ ] **Step 4: Formatter 검사**

```bash
./gradlew spotlessCheck
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 변경 범위와 파일 이동 검증**

```bash
git diff develop...HEAD --stat
git diff --check
git status --short
```

Expected: auth/user 분리와 관련 문서 외 변경 없음, whitespace 오류 없음

- [ ] **Step 6: 문서 커밋 가능 범위 확인**

프로젝트 루트의 `docs`가 backend Git 저장소 밖에 있으므로 해당 테크 스펙은 backend 커밋에 포함할 수 없다. 로컬 반영 결과를 완료 보고에 명시하고, backend 저장소 안의 추가 변경이 있을 때만 다음 커밋을 생성한다.

```bash
git status --short
```

Expected: backend 저장소에 문서 동기화 목적의 미커밋 파일 없음

---

### Task 5: PR 자료 작성

**Files:**
- Verify only: Git commit history and branch diff

**Interfaces:**
- Consumes: 완료된 커밋과 검증 결과
- Produces: 사용자가 직접 생성할 PR 제목과 본문

- [ ] **Step 1: 커밋과 변경 파일 확인**

```bash
git log --oneline develop..HEAD
git diff --name-status develop...HEAD
```

- [ ] **Step 2: PR 제목 작성**

```text
refactor: 회원·인증 도메인 패키지 분리
```

- [ ] **Step 3: PR 본문 작성**

PR 본문에는 `Refs #140`, PR 유형, 변경 사항, 관련 문서, 특별히 봐줄 부분, 실행한 테스트, 기타, 체크리스트를 포함한다. 확인되지 않은 Wiki 상위 이슈는 임의로 연결하지 않는다.
