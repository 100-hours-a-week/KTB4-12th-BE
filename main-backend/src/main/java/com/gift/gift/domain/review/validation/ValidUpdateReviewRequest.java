package com.gift.gift.domain.review.validation;

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
@Constraint(validatedBy = UpdateReviewRequestValidator.class)
public @interface ValidUpdateReviewRequest {

    String message() default "INVALID_REQUEST";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
