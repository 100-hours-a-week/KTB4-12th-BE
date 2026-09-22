# 회원·인증 도메인 성공·오류 코드 분리 설계

## 목표

`auth`와 `user` 도메인의 성공 메시지와 비즈니스 오류 선택을 도메인별 enum으로 관리한다. Controller와 Service에 흩어진 메시지 하드코딩을 제거하면서 기존 API의 HTTP 상태, `message`, `error.code`, `details`, `traceId`, 응답 필드와 Header 계약은 변경하지 않는다.

내부 검증과 예외 진단에 사용되는 영문 메시지는 한국어로 통일한다. 다만 이번 작업에서는 공통 `ErrorCode`를 인터페이스로 전환하거나 외부 오류 코드 체계를 새로 정의하지 않는다.

## 구현 범위

### 포함

- `auth` 도메인 오류 선택을 담당하는 `AuthErrorCode`와 `AuthException`
- `user` 도메인 오류 선택을 담당하는 `UserErrorCode`와 `UserException`
- `auth` 도메인 성공 응답을 담당하는 `AuthSuccessCode`
- `user` 도메인 성공 응답을 담당하는 `UserSuccessCode`
- Controller의 성공 상태·메시지 하드코딩 제거
- Service의 `BusinessException(ErrorCode)` 직접 생성 제거
- 로그인 요청 제한 예외의 도메인 오류 코드 사용
- `auth`, `user`와 이들이 직접 사용하는 공통 응답·예외 처리 코드의 영문 진단 메시지 한국어화
- 변경한 응답 코드와 주요 API 흐름을 검증하는 핵심 테스트
- 내부 구조가 달라진 회원·인증 도메인 테크 스펙 동기화

### 제외

- `global.exception.ErrorCode`를 공통 인터페이스로 전환하는 작업
- `CommonErrorCode`, 공통 `CustomException` 도입
- 외부 `error.code`를 `AUTH-001`, `USER-001` 등의 새 체계로 변경하는 작업
- `gift`, `product`, `friend`, `preference` 등 다른 도메인의 성공·오류 코드 변경
- API 요청·응답 필드, HTTP 상태, Cookie, Header, 보안 정책 변경
- DB 스키마와 마이그레이션 변경
- 전체 테스트 실행

공통 인터페이스와 외부 오류 코드 체계 개편은 `MVP 이후 도메인별 오류 코드 및 공통 예외 처리 리팩터링 프롬프트.md`에 따라 별도 작업으로 수행한다.

## 구조

```text
com/gift/gift/
├── domain/
│   ├── auth/
│   │   ├── exception/
│   │   │   ├── AuthErrorCode.java
│   │   │   ├── AuthException.java
│   │   │   └── LoginRateLimitExceededException.java
│   │   └── response/
│   │       └── AuthSuccessCode.java
│   └── user/
│       ├── exception/
│       │   ├── UserErrorCode.java
│       │   └── UserException.java
│       └── response/
│           └── UserSuccessCode.java
└── global/
    └── exception/
        ├── ErrorCode.java
        ├── BusinessException.java
        └── GlobalExceptionHandler.java
```

`AuthErrorCode`와 `UserErrorCode`는 기존 전역 `ErrorCode`를 구성 요소로 보유한다. 도메인 Service는 전역 enum을 직접 고르는 대신 도메인 enum을 선택하고, 도메인 Exception이 이를 기존 `BusinessException`으로 연결한다.

```text
Service
  → AuthException(AuthErrorCode) 또는 UserException(UserErrorCode)
  → BusinessException
  → GlobalExceptionHandler
  → 기존 API 실패 응답
```

이 구성은 현재 공통 예외 처리기를 재사용하면서 도메인별 오류 선택 지점을 분리한다. 향후 공통 `ErrorCode` 인터페이스를 도입할 때 도메인 enum이 인터페이스를 직접 구현하도록 전환할 수 있다.

## 오류 코드 매핑

### AuthErrorCode

| 도메인 상수 | 기존 전역 ErrorCode | HTTP 상태 | 외부 error.code | 응답 메시지 |
| --- | --- | ---: | --- | --- |
| `INVALID_CREDENTIALS` | `INVALID_CREDENTIALS` | 401 | `INVALID_CREDENTIALS` | 이메일 또는 비밀번호가 일치하지 않습니다. |
| `LOGIN_RATE_LIMIT_EXCEEDED` | `TOO_MANY_REQUESTS` | 429 | `TOO_MANY_REQUESTS` | 로그인 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요. |
| `AUTHENTICATION_TEMPORARILY_UNAVAILABLE` | `AUTHENTICATION_TEMPORARILY_UNAVAILABLE` | 503 | `AUTHENTICATION_TEMPORARILY_UNAVAILABLE` | 인증 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요. |
| `INVALID_REFRESH_TOKEN` | `INVALID_REFRESH_TOKEN` | 401 | `INVALID_REFRESH_TOKEN` | 인증이 만료되었거나 유효하지 않습니다. 다시 로그인해 주세요. |
| `TOKEN_REFRESH_CONFLICT` | `TOKEN_REFRESH_CONFLICT` | 409 | `TOKEN_REFRESH_CONFLICT` | 다른 토큰 재발급 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요. |

`LOGIN_RATE_LIMIT_EXCEEDED`만 기존 `TOO_MANY_REQUESTS`의 기본 메시지 대신 로그인 문맥에 맞는 메시지를 사용한다. `LoginRateLimitExceededException`은 `Retry-After` 값을 보유해야 하므로 별도 예외 클래스를 유지하고 `AuthException`을 상속한다.

### UserErrorCode

| 도메인 상수 | 기존 전역 ErrorCode | HTTP 상태 | 외부 error.code | 응답 메시지 |
| --- | --- | ---: | --- | --- |
| `SIGNUP_TERMS_NOT_FOUND` | `SIGNUP_TERMS_NOT_FOUND` | 404 | `SIGNUP_TERMS_NOT_FOUND` | 현재 적용 중인 회원가입 약관이 없습니다. |
| `INVALID_EMAIL_FORMAT` | `INVALID_EMAIL_FORMAT` | 400 | `INVALID_EMAIL_FORMAT` | 이메일 형식을 확인해 주세요. |
| `EMAIL_ALREADY_IN_USE` | `EMAIL_ALREADY_IN_USE` | 409 | `EMAIL_ALREADY_IN_USE` | 이미 사용 중인 이메일입니다. |
| `INVALID_TERM_VERSION` | `INVALID_TERM_VERSION` | 400 | `INVALID_TERM_VERSION` | 약관이 변경되었습니다. 다시 확인해 주세요. |
| `REQUIRED_TERMS_NOT_AGREED` | `REQUIRED_TERMS_NOT_AGREED` | 400 | `REQUIRED_TERMS_NOT_AGREED` | 필수 약관에 모두 동의해 주세요. |
| `USER_NOT_FOUND` | `USER_NOT_FOUND` | 404 | `USER_NOT_FOUND` | 사용자 정보를 찾을 수 없습니다. |

이메일 사용 가능 확인의 형식 오류는 기존 `RequestValidationException`을 유지한다. `SignupPreflightController`가 `UserErrorCode.INVALID_EMAIL_FORMAT`에서 전역 `ErrorCode`를 꺼내 전달하여 기존 검증 응답 형식을 보존한다.

## 공통 오류 유지 범위

다음 오류는 특정 도메인의 업무 실패가 아니므로 전역 `ErrorCode`와 기존 공통 처리 경로를 유지한다.

- `INVALID_REQUEST`: Request DTO 바인딩·검증 실패
- `INTERNAL_SERVER_ERROR`: 예상하지 못한 시스템 오류
- `UNAUTHORIZED`: Spring Security 공통 인증 실패
- `CSRF_VALIDATION_FAILED`: 공통 보안 요청 검증 실패

`AuthenticationEntryPoint`, Security Filter와 `GlobalExceptionHandler`의 응답 구조는 변경하지 않는다.

## 성공 코드 매핑

### AuthSuccessCode

| 상수 | HTTP 상태 | 응답 메시지 | 사용 API |
| --- | ---: | --- | --- |
| `LOGIN_COMPLETED` | 200 | 로그인에 성공했습니다. | `POST /auth/login` |
| `TOKEN_REFRESHED` | 200 | 토큰을 재발급했습니다. | `POST /auth/refresh` |
| `LOGOUT_COMPLETED` | 200 | 로그아웃이 완료되었습니다. | `POST /auth/logout` |

### UserSuccessCode

| 상수 | HTTP 상태 | 응답 메시지 | 사용 API |
| --- | ---: | --- | --- |
| `SIGNUP_TERMS_RETRIEVED` | 200 | 회원가입 약관을 조회했습니다. | `GET /auth/terms` |
| `EMAIL_AVAILABLE` | 200 | 사용할 수 있는 이메일입니다. | `POST /auth/email-availability` |
| `EMAIL_UNAVAILABLE` | 200 | 이미 사용 중인 이메일입니다. | `POST /auth/email-availability` |
| `SIGNUP_COMPLETED` | 201 | 회원가입이 완료되었습니다. | `POST /auth/signup` |
| `PROFILE_RETRIEVED` | 200 | 내 정보를 조회했습니다. | `GET /users/me` |
| `PROFILE_UPDATED` | 200 | 사용자 정보를 수정했습니다. | `PATCH /users/me` |
| `ONBOARDING_COMPLETED` | 200 | 최초 로그인 설정을 완료했습니다. | `PATCH /users/me/onboarding` |

Controller는 선택한 성공 enum의 상태와 메시지로 `ResponseEntity`와 `ApiResponse`를 생성한다. 이메일 사용 가능 여부처럼 같은 상태 코드에서 메시지가 달라지는 경우 결과에 따라 서로 다른 enum 상수를 선택한다.

## 예외 처리 특수 사례

### LoginRateLimitExceededException

- 외부 오류 코드는 기존과 동일한 `TOO_MANY_REQUESTS`
- 메시지는 로그인 전용 문구 유지
- `Retry-After` Header 계산에 필요한 초 단위 값 유지
- `GlobalExceptionHandler`의 전용 처리 메서드 유지

### RequestValidationException

- `details`가 필요한 검증 오류에만 사용
- `INVALID_EMAIL_FORMAT`의 기존 단일 오류 응답 계약 유지
- 도메인 enum 도입 때문에 `details` 생성 규칙을 변경하지 않음

### SignupTermsConfigurationException

이 예외는 API 명세에 정의된 비즈니스 오류가 아니라 필수 약관 설정이 비정상인 예상 밖의 서버 상태를 나타낸다. `UserErrorCode`로 변환하지 않고 기존 런타임 예외와 500 공통 응답을 유지한다. 내부 영문 메시지만 한국어로 변경한다.

## 한국어 메시지 정책

### 변경 대상

- `Objects.requireNonNull`의 진단 메시지
- `IllegalArgumentException`, `IllegalStateException` 메시지
- auth/user 내부에서 생성하는 예외 원인 메시지
- `ApiResponse`, `ValidationDetail` 등 두 도메인이 직접 사용하는 공통 응답 객체의 불변식 검사 메시지
- `GlobalExceptionHandler`와 auth 정리 배치의 사람이 읽는 로그 문구
- `SignupTermsConfigurationException`의 내부 메시지

이 메시지는 주로 개발자가 로그나 테스트 실패를 통해 확인하는 내부 진단 문구이다. 외부 사용자에게는 예외 처리기를 거친 명세의 한국어 `message`가 반환된다.

### 변경하지 않는 값

- JSON 필드명과 Validation의 `reason` 값
- 외부 `error.code`
- DB 테이블·컬럼·인덱스·제약조건 이름
- 설정 Property 이름
- 알고리즘 이름과 HTTP Method
- 로그 검색과 시스템 연동에 사용되는 기술 식별자
- 인증 정보 마스킹 값인 `REDACTED`

새로 작성하는 auth/user 코드의 사람이 읽는 오류·진단 메시지는 한국어를 기본으로 한다.

## API 호환성

이번 작업 전후로 다음 계약은 동일해야 한다.

- 모든 대상 API의 HTTP 상태
- 성공 응답의 `message`와 `data`
- 실패 응답의 `message`, `error.code`, `error.traceId`, 선택적 `details`
- 로그인 제한 응답의 `Retry-After`
- 인증 Cookie와 Header
- 로그인, 재발급, 로그아웃, 회원가입, 프로필, 온보딩의 처리 흐름

따라서 `docs/api.xlsx`, API Markdown과 Postman 계약은 수정하지 않는다. 패키지 내부의 성공·오류 선택 구조만 변경되므로 회원·인증 도메인 테크 스펙에 해당 구조를 반영한다.

## 테스트 전략

사용자의 요청에 따라 전체 테스트를 실행하지 않고 변경에 직접 관련된 주요 테스트만 실행한다.

- enum 단위 테스트: 상태, 기존 외부 오류 코드, 메시지 매핑 검증
- Controller 핵심 테스트: 성공 응답 상태와 메시지 보존 검증
- Service 핵심 테스트: 기존 실패 조건이 도메인 Exception과 동일 외부 코드로 연결되는지 검증
- 로그인 제한 테스트: 429, 로그인 전용 메시지, `Retry-After` 보존 검증
- 컴파일: 변경된 생성자와 import 누락 확인
- Formatter: 프로젝트에 구성된 Formatter 검사

기존 테스트의 기대값은 API 계약이 실제로 바뀌지 않는 한 변경하지 않는다.

## 단계별 커밋 방향

1. 설계 및 구현 계획 문서
2. auth/user 오류 코드와 도메인 예외 분리
3. auth/user 성공 응답 코드 분리
4. auth/user 및 관련 공통 진단 메시지 한국어화
5. 핵심 응답 계약 테스트 보강
6. 회원·인증 도메인 테크 스펙 동기화

각 커밋은 독립적으로 의미를 설명할 수 있는 작은 단위로 구성하고 커밋 메시지는 `prefix: 한글 작업 내용` 형식을 사용한다.

## 향후 전환

MVP 이후 전체 예외 구조를 리팩터링할 때는 다음 방향으로 확장한다.

```text
global.exception.ErrorCode (interface)
├── CommonErrorCode
├── AuthErrorCode
└── UserErrorCode

CustomException
└── 각 도메인 오류 enum을 직접 수용
```

그 단계에서는 외부 오류 코드 체계와 API 명세 변경 여부를 별도로 확정한다. 이번 PR의 조합 방식은 해당 전환 전까지 기존 계약을 안전하게 보존하기 위한 중간 구조이다.
