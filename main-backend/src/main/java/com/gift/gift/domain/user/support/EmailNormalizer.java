package com.gift.gift.domain.user.support;

import java.util.Locale;
import java.util.Objects;

public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        Objects.requireNonNull(email, "이메일 값이 없습니다.");

        return email.strip().toLowerCase(Locale.ROOT);
    }

}
