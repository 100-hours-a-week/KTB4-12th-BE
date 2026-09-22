package com.gift.gift.domain.gift.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.gift.gift.domain.gift.support.GiftPolicy;
import com.gift.gift.global.exception.ValidationErrorReason;

public record GiftPreflightRequest(
        @NotNull(message = ValidationErrorReason.Message.REQUIRED)
        @Positive(message = ValidationErrorReason.Message.OUT_OF_RANGE)
        Long productId,

        @NotNull(message = ValidationErrorReason.Message.REQUIRED)
        @Positive(message = ValidationErrorReason.Message.OUT_OF_RANGE)
        Long recipientUserId,

        @NotNull(message = ValidationErrorReason.Message.REQUIRED)
        @Positive(message = ValidationErrorReason.Message.OUT_OF_RANGE)
        @Max(value = GiftPolicy.MAX_QUANTITY, message = ValidationErrorReason.Message.OUT_OF_RANGE)
        Integer quantity
) {
}
