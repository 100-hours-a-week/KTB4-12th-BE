package com.gift.gift.global.exception;

import java.util.Objects;

public record ValidationDetail(
        String field,
        ValidationErrorReason reason
) {
    public ValidationDetail {
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        if (field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }
    }
}
