package com.gift.gift.global.exception;

import java.util.Objects;

public record ValidationDetail(
        String field,
        ValidationErrorReason reason
) {
    public ValidationDetail {
        Objects.requireNonNull(field, "필드명은 null일 수 없습니다.");
        Objects.requireNonNull(reason, "검증 실패 사유는 null일 수 없습니다.");

        if (field.isBlank()) {
            throw new IllegalArgumentException("필드명은 비어 있을 수 없습니다.");
        }
    }
}
