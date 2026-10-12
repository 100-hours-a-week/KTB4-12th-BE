package com.gift.payment.payment.service;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.payment.payment.entity.Payment;
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
}
