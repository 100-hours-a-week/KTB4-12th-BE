package com.gift.gift.domain.review.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CreateReviewRequestValidator.class)
public @interface ValidCreateReviewRequest {

    String message() default "INVALID_REQUEST";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
