package com.gift.gift.global.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiResponseTest {

    @Test
    @DisplayName("응답 메시지가 없으면 한국어 진단 메시지를 제공한다")
    void constructor_rejectsNullMessageWithKoreanDiagnostic() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> new ApiResponse<>(null, new Object(), null)
        );

        assertEquals(
                "응답 메시지는 null일 수 없습니다.",
                exception.getMessage()
        );
    }
}
