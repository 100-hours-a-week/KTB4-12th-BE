package com.gift.payment.payment.support;

import java.util.Locale;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import com.gift.payment.global.exception.BusinessException;
import com.gift.payment.global.exception.ErrorCode;

@Component
@RequiredArgsConstructor
public class PaymentScenarioResolver {

    private static final Profiles TEST_PROFILES = Profiles.of("local", "test", "dev");

    private final Environment environment;

    public PaymentScenario resolve(String value) {
        if (value == null) {
            return PaymentScenario.APPROVE;
        }

        if (!environment.acceptsProfiles(TEST_PROFILES)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        try {
            return PaymentScenario.valueOf(
                    value.trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
