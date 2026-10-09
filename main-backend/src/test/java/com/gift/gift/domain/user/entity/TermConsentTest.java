package com.gift.gift.domain.user.entity;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class TermConsentTest {

    @Test
    @DisplayName("약관 동의는 null 회원을 참조할 수 없다")
    void create_rejectsNullUser() {
        Term term = new Term("CODE", 1, "제목", "본문", true);

        assertThrows(NullPointerException.class, () -> new TermConsent(null, term, true));
    }

    @Test
    @DisplayName("약관 동의는 null 약관을 참조할 수 없다")
    void create_rejectsNullTerm() {
        User user = new User("user@example.com", "unused", "김선물", LocalDate.of(2000, 1, 1));

        assertThrows(NullPointerException.class, () -> new TermConsent(user, null, true));
    }
}
