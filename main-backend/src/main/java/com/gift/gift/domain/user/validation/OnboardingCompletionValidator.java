package com.gift.gift.domain.user.validation;

import tools.jackson.databind.JsonNode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import com.gift.gift.domain.user.dto.request.CompleteOnboardingRequest;
import com.gift.gift.global.exception.ValidationErrorReason;

public class OnboardingCompletionValidator
        implements ConstraintValidator<
        ValidOnboardingCompletion,
        CompleteOnboardingRequest
        > {

    @Override
    public boolean isValid(
            CompleteOnboardingRequest request,
            ConstraintValidatorContext context
    ) {
        if (request == null) {
            return true;
        }

        context.disableDefaultConstraintViolation();

        if (!request.unknownFieldNames().isEmpty()) {
            addRequestViolation(
                    context,
                    ValidationErrorReason.Message.UNKNOWN_FIELD
            );
            return false;
        }

        if (!request.hasCompleted()) {
            addCompletedViolation(
                    context,
                    ValidationErrorReason.Message.REQUIRED
            );
            return false;
        }

        JsonNode completed = request.rawCompleted();

        if (completed == null || completed.isNull()) {
            addCompletedViolation(
                    context,
                    ValidationErrorReason.Message.REQUIRED
            );
            return false;
        }

        if (!completed.isBoolean()) {
            addCompletedViolation(
                    context,
                    ValidationErrorReason.Message.INVALID_FORMAT
            );
            return false;
        }

        if (!completed.booleanValue()) {
            addCompletedViolation(
                    context,
                    ValidationErrorReason.Message.INVALID_VALUE
            );
            return false;
        }

        return true;
    }

    private void addCompletedViolation(
            ConstraintValidatorContext context,
            String reason
    ) {
        context.buildConstraintViolationWithTemplate(reason)
                .addPropertyNode("completed")
                .addConstraintViolation();
    }

    private void addRequestViolation(
            ConstraintValidatorContext context,
            String reason
    ) {
        context.buildConstraintViolationWithTemplate(reason)
                .addConstraintViolation();
    }
}
