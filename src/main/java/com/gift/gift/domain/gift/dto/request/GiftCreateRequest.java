package com.gift.gift.domain.gift.dto.request;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.gift.gift.domain.gift.support.GiftPolicy;
import com.gift.gift.global.exception.ValidationErrorReason;

public record GiftCreateRequest(
        @NotNull(message = ValidationErrorReason.Message.REQUIRED)
        @Positive(message = ValidationErrorReason.Message.OUT_OF_RANGE)
        Long productId,

        @NotNull(message = ValidationErrorReason.Message.REQUIRED)
        @Positive(message = ValidationErrorReason.Message.OUT_OF_RANGE)
        Long recipientUserId,

        @NotNull(message = ValidationErrorReason.Message.REQUIRED)
        @Positive(message = ValidationErrorReason.Message.OUT_OF_RANGE)
        @Max(value = GiftPolicy.MAX_QUANTITY, message = ValidationErrorReason.Message.OUT_OF_RANGE)
        Integer quantity,

        @NotNull(message = ValidationErrorReason.Message.REQUIRED)
        @DecimalMin(value = "0", message = ValidationErrorReason.Message.OUT_OF_RANGE)
        @Digits(integer = 12, fraction = 0, message = ValidationErrorReason.Message.INVALID_FORMAT)
        BigDecimal expectedUnitPrice
) {
}
