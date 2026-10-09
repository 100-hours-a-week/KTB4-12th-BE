package com.gift.gift.domain.auth.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthSuccessCodeTest {

    @Test
    @DisplayName("인증 성공 코드는 기존 상태와 메시지를 유지한다")
    void authSuccessCode_preservesExistingApiContract() {
        assertEquals(HttpStatus.OK, AuthSuccessCode.LOGIN_COMPLETED.status());
        assertEquals(
                "로그인에 성공했습니다.",
                AuthSuccessCode.LOGIN_COMPLETED.message()
        );
        assertEquals(HttpStatus.OK, AuthSuccessCode.TOKEN_REFRESHED.status());
        assertEquals(HttpStatus.OK, AuthSuccessCode.LOGOUT_COMPLETED.status());
    }
}
