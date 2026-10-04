package com.gift.gift.domain.review.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.gift.gift.domain.review.validation.ValidCreateReviewRequest;
import com.gift.gift.global.exception.ValidationErrorReason;

@ValidCreateReviewRequest
public record CreateReviewRequest(

        @NotNull(message = ValidationErrorReason.Message.REQUIRED)
        @Min(
                value = 1,
                message = ValidationErrorReason.Message.OUT_OF_RANGE
        )
        @Max(
                value = 5,
                message = ValidationErrorReason.Message.OUT_OF_RANGE
        )
        Integer rating,

        @NotBlank(message = ValidationErrorReason.Message.BLANK_NOT_ALLOWED)
        String content
) {
}
