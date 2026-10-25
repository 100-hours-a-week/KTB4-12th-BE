package com.gift.gift.domain.order.dto.request;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.gift.gift.support.TestValidatorFactory;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCreateRequestTest {

    private static final LocalValidatorFactoryBean VALIDATOR = TestValidatorFactory.create();

    @AfterAll
    static void closeValidator() {
        VALIDATOR.close();
    }

    @Test
    @DisplayName("쿠폰은 미사용 또는 2배·3배만 허용한다")
    void validate_acceptsUnusedDoubleAndTriple() {
        assertThat(VALIDATOR.validate(request(null))).isEmpty();
        assertThat(VALIDATOR.validate(request(2))).isEmpty();
        assertThat(VALIDATOR.validate(request(3))).isEmpty();
    }

    @Test
    @DisplayName("허용 범위를 벗어난 배수는 couponMultiplier 필드 오류다")
    void validate_rejectsUnsupportedMultipliers() {
        for (int multiplier : new int[]{-1, 0, 1, 4, Integer.MAX_VALUE}) {
            assertThat(VALIDATOR.validate(request(multiplier)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("couponMultiplier");
        }
    }

    private PaymentCreateRequest request(Integer multiplier) {
        return new PaymentCreateRequest(101L, 27L, 1, 32_000L, multiplier);
    }
}
