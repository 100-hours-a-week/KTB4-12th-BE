package com.gift.gift.domain.user.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserSuccessCodeTest {

    @Test
    @DisplayName("회원 성공 코드는 기존 상태와 메시지를 유지한다")
    void userSuccessCode_preservesExistingApiContract() {
        assertEquals(
                HttpStatus.CREATED,
                UserSuccessCode.SIGNUP_COMPLETED.status()
        );
        assertEquals(
                "회원가입이 완료되었습니다.",
                UserSuccessCode.SIGNUP_COMPLETED.message()
        );
        assertEquals(HttpStatus.OK, UserSuccessCode.PROFILE_RETRIEVED.status());
        assertEquals(HttpStatus.OK, UserSuccessCode.PROFILE_UPDATED.status());
        assertEquals(HttpStatus.OK, UserSuccessCode.ONBOARDING_COMPLETED.status());
    }

    @Test
    @DisplayName("이메일 사용 가능 여부는 결과별 메시지를 구분한다")
    void emailAvailability_usesResultSpecificMessages() {
        assertEquals(
                "사용할 수 있는 이메일입니다.",
                UserSuccessCode.EMAIL_AVAILABLE.message()
        );
        assertEquals(
                "이미 사용 중인 이메일입니다.",
                UserSuccessCode.EMAIL_UNAVAILABLE.message()
        );
    }
}
