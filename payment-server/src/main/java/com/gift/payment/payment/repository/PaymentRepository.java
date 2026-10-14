package com.gift.payment.payment.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.entity.PaymentStatus;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(String orderId);

    Optional<Payment> findByPaymentKey(String paymentKey);

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update Payment payment
            set payment.status = :canceledStatus,
                payment.canceledAt = :canceledAt,
                payment.updatedAt = :canceledAt
            where payment.id = :paymentId
              and payment.status = :completedStatus
            """)
    int cancel(
            @Param("paymentId") Long paymentId,
            @Param("completedStatus") PaymentStatus completedStatus,
            @Param("canceledStatus") PaymentStatus canceledStatus,
            @Param("canceledAt") LocalDateTime canceledAt
    );
}
