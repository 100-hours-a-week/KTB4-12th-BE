package com.gift.payment.payment.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.gift.payment.global.exception.BusinessException;
import com.gift.payment.global.exception.ErrorCode;
import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.entity.PaymentStatus;
import com.gift.payment.payment.support.PaymentScenario;

@Service
@RequiredArgsConstructor
public class PaymentApprovalService {

    private final PaymentIdempotencyService idempotencyService;
    private final PaymentTransactionService transactionService;

    public Payment confirm(String orderId, Long amount, PaymentScenario scenario) {
        PaymentStartResult result = idempotencyService.start(orderId, amount);

        if (!result.created()) {
            return existing(result.payment());
        }

        return switch (scenario) {
            case APPROVE ->
                    transactionService.approve(result.payment().getId());

            case DECLINE ->
                    decline(result.payment().getId());

            case TEMPORARY_ERROR ->
                    throw new BusinessException(ErrorCode.TEMPORARY_PROCESSING_ERROR);
        };
    }

    private Payment existing(Payment payment) {
        if (payment.getStatus() == PaymentStatus.DECLINED) {
            throw new BusinessException(ErrorCode.PAYMENT_DECLINED);
        }

        return payment;
    }

    private Payment decline(Long paymentId) {
        transactionService.decline(paymentId);

        throw new BusinessException(ErrorCode.PAYMENT_DECLINED);
    }
}
