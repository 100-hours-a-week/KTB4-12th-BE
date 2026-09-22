# 회원·인증 도메인 패키지 분리 설계

## 목표

현재 `com.gift.gift.domain.user`에 함께 있는 회원 기능과 인증 기능을 책임에 따라 `user`와 `auth` 도메인으로 분리한다. 패키지와 import만 재구성하며 API 요청·응답, HTTP 상태, 예외 메시지, 트랜잭션, DB 스키마와 런타임 동작은 변경하지 않는다.

## 도메인 경계

### auth 도메인

사용자의 자격 증명을 확인하고 로그인 상태와 브라우저 인증 세션을 관리한다.

- 로그인, 로그아웃, 토큰 재발급
- Refresh Token과 `UserSession`
- 로그인 IP Token Bucket과 이메일 실패 Backoff
- 인증 데이터 정리 배치
- 로그인 요청 제한 Interceptor

이동 대상은 다음과 같다.

- Controller: `LoginController`, `AuthSessionController`
- Request DTO: `LoginRequest`
- Response DTO: `LoginResponse`, `LoginUserResponse`, `TokenRefreshResponse`
- Entity: `UserSession`, `LoginIpRateLimit`, `LoginEmailFailureLimit`
- Repository: 위 세 Entity의 Repository
- Service: `LoginService`, `LoginSessionService`, `LogoutService`, `TokenRefreshService`, `LoginRateLimiter`, `LoginRateLimitTransactionService`, `AuthenticationDataCleanupTransactionService`
- Support: `LoginResult`, `TokenRefreshResult`, `LoginRateLimitDecision`, `LoginRateLimitProperties`, `RateLimitIdentifierHasher`
- Exception: `LoginRateLimitExceededException`
- Scheduler/Web: `AuthenticationDataCleanupScheduler`, `LoginIpRateLimitInterceptor`

### user 도메인

회원 계정, 가입 정보, 약관 동의, 프로필과 온보딩 상태를 관리한다.

- 회원가입과 가입 전 확인
- 회원 및 상태 관리
- 약관과 약관 동의 이력
- 내 회원정보 조회·수정
- 최초 로그인 온보딩 완료
- 다른 도메인에 제공하는 활성 회원 조회

다음 클래스는 `domain.user`에 유지한다.

- `User`, `UserStatus`, `Term`, `TermConsent`
- `UserRepository`, `TermRepository`, `TermConsentRepository`
- `SignupController`, `SignupPreflightController`, `UserProfileController`
- 회원가입·약관·프로필·온보딩 DTO, Service, Validation
- `AuthenticatedUserService`, `UserQueryService`, `ActiveUserSummary`, `EmailNormalizer`
- `SignupTermsConfigurationException`

회원가입 URL이 `/auth` 아래에 있더라도 회원 및 약관 동의 이력을 생성하는 계정 관리 기능이므로 이번 분리에서는 user 도메인에 유지한다.

## 의존 방향

- auth 도메인은 인증할 회원을 조회하기 위해 user 도메인의 `UserRepository`, `User`, `UserStatus`, `EmailNormalizer`를 사용할 수 있다.
- user 도메인은 auth 도메인의 세션, 토큰, 로그인 제한 구현에 의존하지 않는다.
- preference, gift, friend, product 도메인이 사용하는 `User`, `UserQueryService`, `ActiveUserSummary`의 패키지는 변경하지 않는다.
- Spring Security 필터 체인과 여러 도메인에서 사용하는 JWT 기반 코드는 `global.security`에 유지한다.
- `RefreshCookieProvider`, `RefreshTokenProvider`, `AccessTokenProvider`는 인증 구현과 밀접하지만 이번 작업은 기존 `domain.user` 분리에 한정하므로 `global.security`에 유지한다.
- `GlobalExceptionHandler`, `WebMvcConfig`, `LoginRateLimitConfig`는 이동된 auth 클래스의 import만 갱신한다.

## 구현 전략

1. 패키지 경계를 검증하는 구조 테스트를 먼저 추가해 현재 상태에서 실패함을 확인한다.
2. 인증 Entity·Repository·Support를 `domain.auth`로 이동한다.
3. 인증 Service·Controller·Exception·Scheduler·Web 코드를 `domain.auth`로 이동한다.
4. 대응 테스트 파일을 운영 코드와 같은 `domain.auth` 경로로 이동한다.
5. 전역 설정과 예외 처리기의 import를 수정한다.
6. 컴파일, 관련 테스트, 전체 테스트, Formatter 검사를 수행한다.
7. 실제 설계 변경을 회원·인증 도메인 테크 스펙에 반영한다.

파일 이동은 내용 재작성보다 `git mv`를 우선 사용해 변경 이력을 보존한다. 이동과 무관한 포맷 변경이나 동작 변경은 포함하지 않는다.

## 동작 보존

다음 계약은 변경하지 않는다.

- `/auth/terms`, `/auth/email-availability`, `/auth/signup`
- `/auth/login`, `/auth/refresh`, `/auth/logout`
- `/users/me`, `/users/me/onboarding`
- 요청·응답 JSON과 Cookie 속성
- 성공·실패 메시지, 오류 코드와 `details` 정책
- 로그인 제한과 이메일 Backoff 계산
- 세션 생성·갱신·회전·폐기 정책
- 인증 데이터 정리 기준과 스케줄
- JPA Entity 매핑과 DB 테이블 구조

## 테스트 전략

- 구조 테스트: auth 대상 클래스가 `domain.auth`에 존재하고 이전 `domain.user` 위치에 남지 않았는지 확인
- 컴파일 테스트: 이동된 운영 코드와 테스트의 import 누락 확인
- 관련 테스트: 로그인, 세션, 토큰 재발급, 로그아웃, 로그인 제한, 정리 배치 테스트
- 회귀 테스트: 회원가입, 회원정보, 온보딩 및 다른 도메인의 user 의존 테스트
- 전체 테스트: 기존 API 및 영속성 동작 보존 확인
- Formatter: 이동 과정에서 스타일 위반이 생기지 않았는지 확인

## 문서 범위

API 계약과 ERD는 변경되지 않으므로 프로젝트 루트의 `docs/api.xlsx`, API Markdown, `docs/erd.md`는 수정하지 않는다. 실제 패키지 구조와 도메인 책임이 바뀌므로 프로젝트 루트의 `docs/auth-user_domain_tech_spec.md`에 있는 패키지 구성과 책임 설명만 갱신한다.

## 제외 범위

- 도메인별 성공·오류 enum 도입
- 예외 클래스 통합 또는 메시지 변경
- 공통 ErrorCode 구조 변경
- JWT·Cookie·CORS·CSRF 정책 변경
- DB 마이그레이션
- 다른 도메인의 패키지 리팩터링
