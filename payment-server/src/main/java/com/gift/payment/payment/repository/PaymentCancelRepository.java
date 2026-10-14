package com.gift.payment.payment.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.entity.PaymentCancel;

public interface PaymentCancelRepository extends JpaRepository<PaymentCancel, Long> {

    Optional<PaymentCancel> findByPayment_IdAndIdempotencyKey(Long paymentId, String idempotencyKey);
}
