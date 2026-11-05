package com.gift.gift.domain.order.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.entity.PaymentCancellationFailureCode;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;

public interface PaymentCancellationRepository
        extends JpaRepository<PaymentCancellation, Long> {

    Optional<PaymentCancellation> findByRequesterIdAndIdempotencyKey(
            Long requesterId,
            String idempotencyKey
    );

    Optional<PaymentCancellation>
    findFirstByOrderRecordIdAndStatusOrderByUpdatedAtDescIdDesc(
            Long orderRecordId,
            PaymentCancellationStatus status
    );

    Optional<PaymentCancellation>
    findFirstByOrderRecordIdOrderByUpdatedAtDescIdDesc(Long orderRecordId);

    boolean existsByOrderRecordIdAndStatus(
            Long orderRecordId,
            PaymentCancellationStatus status
    );

    @Query("""
            select cancellation
            from PaymentCancellation cancellation
            where cancellation.cancellationSource = :source
              and cancellation.status = :pendingStatus
              and cancellation.nextReconciliationAt <= :now
              and (
                    cancellation.failureCode is null
                    or cancellation.failureCode <> :requiredCode
              )
            order by cancellation.nextReconciliationAt asc, cancellation.id asc
            """)
    List<PaymentCancellation> findDueReconciliationCandidates(
            @Param("source") CancellationSource source,
            @Param("pendingStatus") PaymentCancellationStatus pendingStatus,
            @Param("requiredCode") PaymentCancellationFailureCode requiredCode,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PaymentCancellation cancellation
            set cancellation.reconciliationStartedAt =
                    coalesce(cancellation.reconciliationStartedAt, :startedAt),
                cancellation.nextReconciliationAt = :nextAt,
                cancellation.reconciliationAttempts = 0,
                cancellation.recoveryCancelPostAttempts = 0
            where cancellation.id = :cancellationId
              and cancellation.status =
                    com.gift.gift.domain.order.entity.PaymentCancellationStatus.PENDING
              and cancellation.nextReconciliationAt is null
            """)
    int startReconciliation(
            @Param("cancellationId") Long cancellationId,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("nextAt") LocalDateTime nextAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PaymentCancellation cancellation
            set cancellation.status =
                    :completedStatus,
                cancellation.cancelTransactionKey = :transactionKey,
                cancellation.cancelAmount = :cancelAmount,
                cancellation.failureCode = null,
                cancellation.canceledAt = :canceledAt,
                cancellation.nextReconciliationAt = null,
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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PaymentCancellation cancellation
            set cancellation.cancelTransactionKey = :transactionKey,
                cancellation.cancelAmount = :cancelAmount,
                cancellation.failureCode = null,
                cancellation.canceledAt = :canceledAt,
                cancellation.reconciliationStartedAt =
                    coalesce(cancellation.reconciliationStartedAt, :now),
                cancellation.nextReconciliationAt = :nextAt,
                cancellation.updatedAt = :now
            where cancellation.id = :cancellationId
              and cancellation.status = :pendingStatus
              and (
                    cancellation.cancelTransactionKey is null
                    or cancellation.cancelTransactionKey = :transactionKey
              )
            """)
    int storeRemoteResult(
            @Param("cancellationId") Long cancellationId,
            @Param("pendingStatus") PaymentCancellationStatus pendingStatus,
            @Param("transactionKey") String transactionKey,
            @Param("cancelAmount") long cancelAmount,
            @Param("canceledAt") LocalDateTime canceledAt,
            @Param("now") LocalDateTime now,
            @Param("nextAt") LocalDateTime nextAt
    );

    default int storeRemoteResult(
            Long cancellationId,
            PaymentCancellationStatus pendingStatus,
            String transactionKey,
            long cancelAmount,
            LocalDateTime canceledAt
    ) {
        return storeRemoteResult(
                cancellationId,
                pendingStatus,
                transactionKey,
                cancelAmount,
                canceledAt,
                canceledAt,
                canceledAt.plusSeconds(5)
        );
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PaymentCancellation cancellation
            set cancellation.failureCode = :failureCode,
                cancellation.reconciliationStartedAt =
                    coalesce(cancellation.reconciliationStartedAt, :now),
                cancellation.nextReconciliationAt = :nextAt,
                cancellation.updatedAt = :now
            where cancellation.id = :cancellationId
              and cancellation.status = :pendingStatus
            """)
    int markUnconfirmed(
            @Param("cancellationId") Long cancellationId,
            @Param("pendingStatus") PaymentCancellationStatus pendingStatus,
            @Param("failureCode") PaymentCancellationFailureCode failureCode,
            @Param("now") LocalDateTime now,
            @Param("nextAt") LocalDateTime nextAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PaymentCancellation cancellation
            set cancellation.failureCode = :failureCode,
                cancellation.nextReconciliationAt = :nextAt,
                cancellation.reconciliationAttempts =
                    cancellation.reconciliationAttempts + 1,
                cancellation.updatedAt = :now
            where cancellation.id = :cancellationId
              and cancellation.status =
                    com.gift.gift.domain.order.entity.PaymentCancellationStatus.PENDING
              and (
                    cancellation.failureCode is null
                    or cancellation.failureCode <>
                        com.gift.gift.domain.order.entity.PaymentCancellationFailureCode.CANCELLATION_RECONCILIATION_REQUIRED
              )
            """)
    int scheduleReconciliation(
            @Param("cancellationId") Long cancellationId,
            @Param("failureCode") PaymentCancellationFailureCode failureCode,
            @Param("now") LocalDateTime now,
            @Param("nextAt") LocalDateTime nextAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PaymentCancellation cancellation
            set cancellation.recoveryCancelPostAttempts =
                    cancellation.recoveryCancelPostAttempts + 1,
                cancellation.updatedAt = :now
            where cancellation.id = :cancellationId
              and cancellation.status =
                    com.gift.gift.domain.order.entity.PaymentCancellationStatus.PENDING
              and cancellation.cancelTransactionKey is null
              and cancellation.recoveryCancelPostAttempts < :maxAttempts
            """)
    int reserveRecoveryCancelPost(
            @Param("cancellationId") Long cancellationId,
            @Param("maxAttempts") int maxAttempts,
            @Param("now") LocalDateTime now
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PaymentCancellation cancellation
            set cancellation.failureCode =
                    com.gift.gift.domain.order.entity.PaymentCancellationFailureCode.CANCELLATION_RECONCILIATION_REQUIRED,
                cancellation.nextReconciliationAt = null,
                cancellation.updatedAt = :now
            where cancellation.id = :cancellationId
              and cancellation.status =
                    com.gift.gift.domain.order.entity.PaymentCancellationStatus.PENDING
            """)
    int requireReview(
            @Param("cancellationId") Long cancellationId,
            @Param("now") LocalDateTime now
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
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

    boolean existsByOrderRecordIdAndStatusIn(
            Long orderRecordId,
            List<PaymentCancellationStatus> statuses
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PaymentCancellation cancellation
            set cancellation.failureCode =
                    com.gift.gift.domain.order.entity.PaymentCancellationFailureCode.CANCELLATION_RESULT_UNCONFIRMED,
                cancellation.updatedAt = :updatedAt
            where cancellation.id = :cancellationId
              and cancellation.cancellationSource =
                    com.gift.gift.domain.order.entity.CancellationSource.USER
              and cancellation.status =
                    com.gift.gift.domain.order.entity.PaymentCancellationStatus.PENDING
              and cancellation.failureCode is null
            """)
    int claimUserRequest(
            @Param("cancellationId") Long cancellationId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PaymentCancellation cancellation
            set cancellation.status =
                    com.gift.gift.domain.order.entity.PaymentCancellationStatus.DECLINED,
                cancellation.failureCode =
                    com.gift.gift.domain.order.entity.PaymentCancellationFailureCode.CANCELLATION_DECLINED,
                cancellation.nextReconciliationAt = null,
                cancellation.updatedAt = :updatedAt
            where cancellation.id = :cancellationId
              and cancellation.status =
                    com.gift.gift.domain.order.entity.PaymentCancellationStatus.PENDING
            """)
    int decline(
            @Param("cancellationId") Long cancellationId,
            @Param("updatedAt") LocalDateTime updatedAt
    );
}
