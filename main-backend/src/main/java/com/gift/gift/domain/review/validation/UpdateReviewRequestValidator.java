package com.gift.gift.domain.review.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import com.gift.gift.domain.review.dto.request.UpdateReviewRequest;
import com.gift.gift.global.exception.ValidationErrorReason;

public class UpdateReviewRequestValidator
        implements ConstraintValidator<ValidUpdateReviewRequest, UpdateReviewRequest> {

    private static final int MAX_CONTENT_LENGTH = 300;

    @Override
    public boolean isValid(UpdateReviewRequest request, ConstraintValidatorContext context) {
        if (request == null) { return true; }

        String content = request.content();

        if (content == null || content.isBlank()) { return true; }

        if (content.codePointCount(0, content.length()) > MAX_CONTENT_LENGTH) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                            ValidationErrorReason.Message.MAX_LENGTH_EXCEEDED
                    )
                    .addPropertyNode("content")
                    .addConstraintViolation();
            return false;
        }

        return true;
    }
}
