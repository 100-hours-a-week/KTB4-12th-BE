package com.gift.gift.domain.user.validation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import com.gift.gift.global.exception.ValidationErrorReason;

public class SignupBirthValidator
        implements ConstraintValidator<ValidSignupBirth, String> {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd")
                    .withResolverStyle(ResolverStyle.STRICT);

    private final Clock clock;

    public SignupBirthValidator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean isValid(
            String value,
            ConstraintValidatorContext context
    ) {
        if (value == null || value.isBlank()) {
            return true;
        }

        LocalDate birth;

        try {
            birth = LocalDate.parse(value, FORMAT);
        } catch (DateTimeParseException exception) {
            return violation(
                    context,
                    ValidationErrorReason.INVALID_FORMAT
            );
        }

        LocalDate today = LocalDate.now(clock);

        if (birth.isAfter(today)
                || birth.isBefore(today.minusYears(120))) {
            return violation(
                    context,
                    ValidationErrorReason.OUT_OF_RANGE
            );
        }

        if (birth.isAfter(today.minusYears(14))) {
            return violation(
                    context,
                    ValidationErrorReason.AGE_REQUIREMENT_NOT_MET
            );
        }

        return true;
    }

    private boolean violation(
            ConstraintValidatorContext context,
            ValidationErrorReason reason
    ) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(reason.name())
                .addConstraintViolation();

        return false;
    }
}
