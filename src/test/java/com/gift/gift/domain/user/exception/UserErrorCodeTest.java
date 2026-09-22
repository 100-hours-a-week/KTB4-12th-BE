package com.gift.gift.domain.user.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.global.exception.ErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserErrorCodeTest {

    @Test
    @DisplayName("회원 오류 코드는 기존 외부 오류 코드와 메시지를 유지한다")
    void userErrorCode_preservesExistingApiContract() {
        assertEquals(
                ErrorCode.SIGNUP_TERMS_NOT_FOUND,
                UserErrorCode.SIGNUP_TERMS_NOT_FOUND.errorCode()
        );
        assertEquals(
                "현재 적용 중인 회원가입 약관이 없습니다.",
                UserErrorCode.SIGNUP_TERMS_NOT_FOUND.message()
        );
        assertEquals(
                ErrorCode.USER_NOT_FOUND,
                UserErrorCode.USER_NOT_FOUND.errorCode()
        );
        assertEquals(
                "사용자 정보를 찾을 수 없습니다.",
                UserErrorCode.USER_NOT_FOUND.message()
        );
    }
}
