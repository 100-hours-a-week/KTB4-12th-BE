package com.gift.payment.payment.support;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.gift.payment.global.exception.BusinessException;
import com.gift.payment.global.exception.ErrorCode;

@Component
@RequiredArgsConstructor
public class PaymentScenarioDelayer {

    private final PaymentScenarioProperties properties;

    public void delay() {
        try {
            Thread.sleep(
                    properties.delay().toMillis()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new BusinessException(
                    ErrorCode.TEMPORARY_PROCESSING_ERROR
            );
        }
    }
}
