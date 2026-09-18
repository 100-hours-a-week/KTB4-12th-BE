package com.gift.gift.domain.user.validation;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class BirthDateValidator
        implements ConstraintValidator<ValidBirthDate, String> {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd")
                    .withResolverStyle(ResolverStyle.STRICT);

    @Override
    public boolean isValid(
            String value,
            ConstraintValidatorContext context
    ) {
        if (value == null || value.isBlank()) {
            return true;
        }

        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            return false;
        }

        try {
            LocalDate.parse(value, FORMAT);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }
}
