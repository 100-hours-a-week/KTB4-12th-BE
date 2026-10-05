package com.gift.gift.domain.recommendation.validation;

import java.util.HashSet;
import java.util.Set;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import tools.jackson.databind.JsonNode;

import com.gift.gift.domain.recommendation.dto.request.ProfileCallbackRequest;
import com.gift.gift.global.exception.ValidationErrorReason;

public class ProfileCallbackRequestValidator
        implements ConstraintValidator<
        ValidProfileCallbackRequest,
        ProfileCallbackRequest
        > {

    private static final int MAX_PRODUCT_COUNT = 30;

    @Override
    public boolean isValid(
            ProfileCallbackRequest request,
            ConstraintValidatorContext context
    ) {
        if (request == null) {
            return true;
        }

        context.disableDefaultConstraintViolation();

        boolean valid = validateInteger(
                request.rawRecipientUserId(),
                "recipientUserId",
                1,
                context
        );

        valid &= validateInteger(
                request.rawSourceVersion(),
                "sourceVersion",
                0,
                context
        );

        valid &= validateStatus(
                request.rawProfileStatus(),
                context
        );

        valid &= validateProductIds(
                request.rawRecommendedProductIds(),
                context
        );

        return valid;
    }

    private boolean validateInteger(
            JsonNode value,
            String field,
            long minimum,
            ConstraintValidatorContext context
    ) {
        if (value == null || value.isNull()) {
            addViolation(
                    context,
                    field,
                    ValidationErrorReason.Message.REQUIRED
            );
            return false;
        }

        if (!value.isIntegralNumber()) {
            addViolation(
                    context,
                    field,
                    ValidationErrorReason.Message.INVALID_TYPE
            );
            return false;
        }

        if (!value.canConvertToLong() || value.longValue() < minimum) {
            addViolation(
                    context,
                    field,
                    ValidationErrorReason.Message.OUT_OF_RANGE
            );
            return false;
        }

        return true;
    }

    private boolean validateStatus(
            JsonNode status,
            ConstraintValidatorContext context
    ) {
        String field = "profileStatus";

        if (status == null || status.isNull()) {
            addViolation(
                    context,
                    field,
                    ValidationErrorReason.Message.REQUIRED
            );
            return false;
        }

        if (!status.isString()) {
            addViolation(
                    context,
                    field,
                    ValidationErrorReason.Message.INVALID_TYPE
            );
            return false;
        }

        if (!"COMPLETED".equals(status.textValue())) {
            addViolation(
                    context,
                    field,
                    ValidationErrorReason.Message.INVALID_VALUE
            );
            return false;
        }

        return true;
    }

    private boolean validateProductIds(
            JsonNode productIds,
            ConstraintValidatorContext context
    ) {
        String field = "recommendedProductIds";

        if (productIds == null || productIds.isNull()) {
            addViolation(
                    context,
                    field,
                    ValidationErrorReason.Message.REQUIRED
            );
            return false;
        }

        if (!productIds.isArray()) {
            addViolation(
                    context,
                    field,
                    ValidationErrorReason.Message.INVALID_TYPE
            );
            return false;
        }

        if (productIds.size() > MAX_PRODUCT_COUNT) {
            addViolation(
                    context,
                    field,
                    ValidationErrorReason.Message.MAX_LENGTH_EXCEEDED
            );
            return false;
        }

        boolean valid = true;
        Set<Long> uniqueIds = new HashSet<>();

        for (JsonNode productId : productIds) {
            if (!validateInteger(productId, field, 1, context)) {
                valid = false;
                continue;
            }

            if (!uniqueIds.add(productId.longValue())) {
                addViolation(
                        context,
                        field,
                        ValidationErrorReason.Message.INVALID_VALUE
                );
                valid = false;
            }
        }

        return valid;
    }

    private void addViolation(
            ConstraintValidatorContext context,
            String field,
            String reason
    ) {
        context.buildConstraintViolationWithTemplate(reason)
                .addPropertyNode(field)
                .addConstraintViolation();
    }
}
