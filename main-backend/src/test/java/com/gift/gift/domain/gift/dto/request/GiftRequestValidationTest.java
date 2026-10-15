package com.gift.gift.domain.gift.dto.request;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.global.exception.ValidationErrorReason;

import static org.assertj.core.api.Assertions.assertThat;

class GiftRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("선물 사전 검증 요청의 필수값이 없으면 REQUIRED 오류가 발생한다")
    void preflightRequest_fails_whenRequiredValuesAreMissing() {
        GiftPreflightRequest request = new GiftPreflightRequest(null, null, null);

        Set<ConstraintViolation<GiftPreflightRequest>> violations = validator.validate(request);

        assertThat(violations)
                .hasSize(3)
                .allMatch(violation -> violation.getMessage().equals(ValidationErrorReason.Message.REQUIRED));
    }

    @Test
    @DisplayName("선물 사전 검증 요청의 수량이 10개를 초과하면 OUT_OF_RANGE 오류가 발생한다")
    void preflightRequest_fails_whenQuantityExceedsMaximum() {
        GiftPreflightRequest request = new GiftPreflightRequest(1L, 2L, 11);

        Set<ConstraintViolation<GiftPreflightRequest>> violations = validator.validate(request);

        assertThat(violations)
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.getPropertyPath().toString()).isEqualTo("quantity");
                    assertThat(violation.getMessage()).isEqualTo(ValidationErrorReason.Message.OUT_OF_RANGE);
                });
    }

    @Test
    @DisplayName("선물 사전 검증 요청의 수량이 최대 수량 10개이면 검증에 성공한다")
    void preflightRequest_succeeds_whenQuantityEqualsMaximum() {
        GiftPreflightRequest request = new GiftPreflightRequest(1L, 2L, 10);

        assertThat(validator.validate(request)).isEmpty();
    }
}
