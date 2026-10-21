package com.gift.payment.payment.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.payment.global.exception.BusinessException;
import com.gift.payment.global.exception.ErrorCode;
import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.entity.PaymentCancel;
import com.gift.payment.payment.repository.PaymentCancelRepository;
import com.gift.payment.payment.repository.PaymentRepository;

@Service
@RequiredArgsConstructor
public class PaymentQueryService {

    private final PaymentRepository paymentRepository;
    private final PaymentCancelRepository paymentCancelRepository;

    @Transactional(readOnly = true)
    public Payment getByOrderId(String orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() ->
                        new BusinessException(
                                ErrorCode.PAYMENT_NOT_FOUND
                        )
                );
    }

    @Transactional(readOnly = true)
    public PaymentCancel getCancellation(String paymentKey, String idempotencyKey) {
        return paymentCancelRepository
                .findByPayment_PaymentKeyAndIdempotencyKey(
                        paymentKey,
                        idempotencyKey
                )
                .orElseThrow(() ->
                        new BusinessException(
                                ErrorCode.PAYMENT_NOT_FOUND
                        )
                );
    }
}
