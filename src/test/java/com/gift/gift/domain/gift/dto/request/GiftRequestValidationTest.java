package com.gift.gift.domain.gift.dto.request;

import java.math.BigDecimal;
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
    @DisplayName("선물 생성 요청의 수량과 식별자가 양수가 아니면 OUT_OF_RANGE 오류가 발생한다")
    void createRequest_fails_whenPositiveValuesAreOutOfRange() {
        GiftCreateRequest request = new GiftCreateRequest(0L, -1L, 0, BigDecimal.ZERO);

        Set<ConstraintViolation<GiftCreateRequest>> violations = validator.validate(request);

        assertThat(violations)
                .hasSize(3)
                .allMatch(violation -> violation.getMessage().equals(ValidationErrorReason.Message.OUT_OF_RANGE));
    }

    @Test
    @DisplayName("예상 상품 가격에 소수점이 있으면 INVALID_FORMAT 오류가 발생한다")
    void createRequest_fails_whenExpectedUnitPriceHasFraction() {
        GiftCreateRequest request = new GiftCreateRequest(1L, 2L, 1, new BigDecimal("1000.50"));

        Set<ConstraintViolation<GiftCreateRequest>> violations = validator.validate(request);

        assertThat(violations)
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.getPropertyPath().toString()).isEqualTo("expectedUnitPrice");
                    assertThat(violation.getMessage())
                            .isEqualTo(ValidationErrorReason.Message.INVALID_FORMAT);
                });
    }
}
