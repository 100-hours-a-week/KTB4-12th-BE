package com.gift.gift.global.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValidationDetailTest {

    @Test
    @DisplayName("검증 필드명이 없으면 한국어 진단 메시지를 제공한다")
    void constructor_rejectsNullFieldWithKoreanDiagnostic() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> new ValidationDetail(
                        null,
                        ValidationErrorReason.REQUIRED
                )
        );

        assertEquals(
                "필드명은 null일 수 없습니다.",
                exception.getMessage()
        );
    }
}
