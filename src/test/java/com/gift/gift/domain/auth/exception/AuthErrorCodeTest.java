package com.gift.gift.domain.auth.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.global.exception.ErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthErrorCodeTest {

    @Test
    @DisplayName("인증 오류 코드는 기존 외부 오류 코드와 메시지를 유지한다")
    void authErrorCode_preservesExistingApiContract() {
        assertEquals(
                ErrorCode.INVALID_CREDENTIALS,
                AuthErrorCode.INVALID_CREDENTIALS.errorCode()
        );
        assertEquals(
                "이메일 또는 비밀번호가 일치하지 않습니다.",
                AuthErrorCode.INVALID_CREDENTIALS.message()
        );
        assertEquals(
                ErrorCode.TOKEN_REFRESH_CONFLICT,
                AuthErrorCode.TOKEN_REFRESH_CONFLICT.errorCode()
        );
    }

    @Test
    @DisplayName("로그인 요청 제한은 공통 코드와 로그인 전용 메시지를 사용한다")
    void loginRateLimit_usesAuthSpecificMessage() {
        assertEquals(
                ErrorCode.TOO_MANY_REQUESTS,
                AuthErrorCode.LOGIN_RATE_LIMIT_EXCEEDED.errorCode()
        );
        assertEquals(
                "로그인 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
                AuthErrorCode.LOGIN_RATE_LIMIT_EXCEEDED.message()
        );
    }

    @Test
    @DisplayName("재시도 시간이 올바르지 않으면 한국어 진단 메시지를 제공한다")
    void loginRateLimitException_rejectsInvalidRetryAfterWithKoreanDiagnostic() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new LoginRateLimitExceededException(0)
        );

        assertEquals(
                "재시도 대기 시간은 1초 이상이어야 합니다.",
                exception.getMessage()
        );
    }
}
