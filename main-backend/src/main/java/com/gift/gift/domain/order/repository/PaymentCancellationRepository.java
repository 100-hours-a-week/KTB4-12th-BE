package com.gift.gift.domain.order.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.gift.domain.order.entity.PaymentCancellation;

public interface PaymentCancellationRepository extends JpaRepository<PaymentCancellation, Long> {

    Optional<PaymentCancellation> findByRequesterIdAndIdempotencyKey(Long requesterId, String idempotencyKey);
}
