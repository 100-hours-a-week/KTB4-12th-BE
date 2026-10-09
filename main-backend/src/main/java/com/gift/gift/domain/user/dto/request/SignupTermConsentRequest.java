package com.gift.gift.domain.user.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.gift.gift.global.exception.ValidationErrorReason;

public record SignupTermConsentRequest(
        @NotNull(message = ValidationErrorReason.Message.REQUIRED)
        @Positive(message = ValidationErrorReason.Message.OUT_OF_RANGE)
        Long termId,

        @NotNull(message = ValidationErrorReason.Message.REQUIRED)
        @Positive(message = ValidationErrorReason.Message.OUT_OF_RANGE)
        Integer version,

        @NotNull(message = ValidationErrorReason.Message.REQUIRED)
        Boolean isAgreed
) {
}
