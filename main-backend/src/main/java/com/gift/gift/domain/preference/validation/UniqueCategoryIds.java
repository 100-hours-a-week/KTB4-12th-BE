package com.gift.gift.domain.preference.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import com.gift.gift.global.exception.ValidationErrorReason;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Documented
@Constraint(validatedBy = UniqueCategoryIdsValidator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface UniqueCategoryIds {

    String message() default
            ValidationErrorReason.Message.DUPLICATE_CATEGORY_ID;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
