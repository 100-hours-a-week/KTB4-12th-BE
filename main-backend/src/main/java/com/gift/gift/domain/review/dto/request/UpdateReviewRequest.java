package com.gift.gift.domain.review.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.gift.gift.domain.review.validation.ValidUpdateReviewRequest;
import com.gift.gift.global.exception.ValidationErrorReason;

@ValidUpdateReviewRequest
public record UpdateReviewRequest(

        @Min(
                value = 1,
                message = ValidationErrorReason.Message.OUT_OF_RANGE
        )
        @Max(
                value = 5,
                message = ValidationErrorReason.Message.OUT_OF_RANGE
        )
        Integer rating,

        String content
) {
}
