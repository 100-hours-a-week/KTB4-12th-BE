package com.gift.gift.domain.user.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = OnboardingCompletionValidator.class)
public @interface ValidOnboardingCompletion {

    String message() default "INVALID_REQUEST";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
