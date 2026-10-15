package com.gift.gift.domain.order.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.entity.PaymentCancellationFailureCode;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;

public interface PaymentCancellationRepository extends JpaRepository<PaymentCancellation, Long> {

    Optional<PaymentCancellation> findByRequesterIdAndIdempotencyKey(Long requesterId, String idempotencyKey);

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update PaymentCancellation cancellation
            set cancellation.status = :completedStatus,
                cancellation.cancelTransactionKey = :transactionKey,
                cancellation.cancelAmount = :cancelAmount,
                cancellation.failureCode = null,
                cancellation.canceledAt = :canceledAt,
                cancellation.updatedAt = :canceledAt
            where cancellation.id = :cancellationId
              and cancellation.status = :pendingStatus
            """)
    int complete(
            @Param("cancellationId") Long cancellationId,
            @Param("pendingStatus") PaymentCancellationStatus pendingStatus,
            @Param("completedStatus") PaymentCancellationStatus completedStatus,
            @Param("transactionKey") String transactionKey,
            @Param("cancelAmount") long cancelAmount,
            @Param("canceledAt") LocalDateTime canceledAt
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update PaymentCancellation cancellation
            set cancellation.failureCode = :failureCode,
                cancellation.updatedAt = :updatedAt
            where cancellation.id = :cancellationId
              and cancellation.status = :pendingStatus
            """)
    int markFailure(
            @Param("cancellationId") Long cancellationId,
            @Param("pendingStatus") PaymentCancellationStatus pendingStatus,
            @Param("failureCode") PaymentCancellationFailureCode failureCode,
            @Param("updatedAt") LocalDateTime updatedAt
    );
}
