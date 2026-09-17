package com.gift.gift.domain.user.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmailNormalizerTest {

    @Test
    @DisplayName("이메일의 앞뒤 공백을 제거하고 소문자로 변환한다")
    void normalize_trimsAndLowercasesEmail() {
        String result = EmailNormalizer.normalize("  User@Example.com  ");

        assertEquals("user@example.com", result);
    }

    @Test
    @DisplayName("이메일 제공자별 주소 변환은 적용하지 않는다")
    void normalize_preservesDotsAndPlusTags() {
        String result = EmailNormalizer.normalize("User.Name+Tag@Example.com");

        assertEquals("user.name+tag@example.com", result);
    }

    @Test
    @DisplayName("null 이메일은 정규화할 수 없다")
    void normalize_rejectsNull() {
        assertThrows(
                NullPointerException.class,
                () -> EmailNormalizer.normalize(null)
        );
    }
}
