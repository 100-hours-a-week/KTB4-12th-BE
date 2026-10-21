package com.gift.payment.payment.service;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.entity.PaymentFailureCode;
import com.gift.payment.payment.repository.PaymentRepository;

@Service
@RequiredArgsConstructor
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment create(String orderId, String requestHash, Long amount) {
        Payment payment = new Payment(
                orderId,
                UUID.randomUUID().toString(),
                requestHash,
                amount
        );

        return paymentRepository.saveAndFlush(payment);
    }

    @Transactional
    public Payment approve(Long paymentId) {
        Payment payment = getPayment(paymentId);

        payment.approve(UUID.randomUUID().toString(), LocalDateTime.now());

        return payment;
    }

    @Transactional
    public Payment decline(Long paymentId) {
        Payment payment = getPayment(paymentId);

        payment.decline(PaymentFailureCode.PAYMENT_DECLINED);

        return payment;
    }

    private Payment getPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("생성한 결제를 찾을 수 없습니다."));
    }
}
