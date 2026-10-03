package com.gift.gift.domain.review.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import com.gift.gift.domain.review.dto.request.CreateReviewRequest;
import com.gift.gift.global.exception.ValidationErrorReason;

public class CreateReviewRequestValidator
        implements ConstraintValidator<ValidCreateReviewRequest, CreateReviewRequest> {

    private static final int MAX_CONTENT_LENGTH = 300;

    @Override
    public boolean isValid(CreateReviewRequest request, ConstraintValidatorContext context) {
        if (request == null) { return true; }

        String content = request.content();

        if (content == null || content.isEmpty()) { return true; }

        context.disableDefaultConstraintViolation();

        if (content.isBlank()) {
            addViolation(
                    context,
                    ValidationErrorReason.Message.INVALID_VALUE
            );
            return false;
        }

        if (content.codePointCount(0, content.length()) > MAX_CONTENT_LENGTH) {
            addViolation(
                    context,
                    ValidationErrorReason.Message.MAX_LENGTH_EXCEEDED
            );
            return false;
        }

        return true;
    }

    private void addViolation(ConstraintValidatorContext context, String reason) {
        context.buildConstraintViolationWithTemplate(reason)
                .addPropertyNode("content")
                .addConstraintViolation();
    }
}
