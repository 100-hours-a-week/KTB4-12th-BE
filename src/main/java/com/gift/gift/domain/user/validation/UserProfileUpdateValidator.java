package com.gift.gift.domain.user.validation;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import com.gift.gift.domain.user.dto.request.UpdateUserProfileRequest;
import com.gift.gift.global.exception.ValidationErrorReason;

public class UserProfileUpdateValidator
        implements ConstraintValidator<
        ValidUserProfileUpdate,
        UpdateUserProfileRequest
        > {

    private static final Pattern BIRTH_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    @Override
    public boolean isValid(
            UpdateUserProfileRequest request,
            ConstraintValidatorContext context
    ) {
        if (request == null) {
            return true;
        }

        context.disableDefaultConstraintViolation();

        boolean valid = true;

        if (!request.hasBirth()
                && !request.hasBirthdayPublic()
                && request.unknownFieldNames().isEmpty()) {
            addRequestViolation(
                    context,
                    ValidationErrorReason.Message.EMPTY_UPDATE_FIELDS
            );
            valid = false;
        }

        for (String field : request.unknownFieldNames()) {
            addFieldViolation(
                    context,
                    field,
                    ValidationErrorReason.Message.UNKNOWN_FIELD
            );
            valid = false;
        }

        if (request.hasBirth()) {
            valid = validateBirth(request.rawBirth(), context)
                    && valid;
        }

        if (request.hasBirthdayPublic()) {
            valid = validateBirthdayPublic(
                    request.rawBirthdayPublic(),
                    context
            ) && valid;
        }

        return valid;
    }

    private boolean validateBirth(
            JsonNode birth,
            ConstraintValidatorContext context
    ) {
        if (birth == null || birth.isNull()) {
            addFieldViolation(
                    context,
                    "birth",
                    ValidationErrorReason.Message.REQUIRED
            );
            return false;
        }

        if (!birth.isTextual()) {
            addFieldViolation(
                    context,
                    "birth",
                    ValidationErrorReason.Message.INVALID_TYPE
            );
            return false;
        }

        String value = birth.textValue();

        if (!BIRTH_PATTERN.matcher(value).matches()) {
            addFieldViolation(
                    context,
                    "birth",
                    ValidationErrorReason.Message.INVALID_FORMAT
            );
            return false;
        }

        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException exception) {
            addFieldViolation(
                    context,
                    "birth",
                    ValidationErrorReason.Message.INVALID_DATE
            );
            return false;
        }
    }

    private boolean validateBirthdayPublic(
            JsonNode birthdayPublic,
            ConstraintValidatorContext context
    ) {
        if (birthdayPublic == null || birthdayPublic.isNull()) {
            addFieldViolation(
                    context,
                    "isBirthdayPublic",
                    ValidationErrorReason.Message.REQUIRED
            );
            return false;
        }

        if (!birthdayPublic.isBoolean()) {
            addFieldViolation(
                    context,
                    "isBirthdayPublic",
                    ValidationErrorReason.Message.INVALID_TYPE
            );
            return false;
        }

        return true;
    }

    private void addRequestViolation(
            ConstraintValidatorContext context,
            String reason
    ) {
        context.buildConstraintViolationWithTemplate(reason)
                .addConstraintViolation();
    }

    private void addFieldViolation(
            ConstraintValidatorContext context,
            String field,
            String reason
    ) {
        context.buildConstraintViolationWithTemplate(reason)
                .addPropertyNode(field)
                .addConstraintViolation();
    }
}
