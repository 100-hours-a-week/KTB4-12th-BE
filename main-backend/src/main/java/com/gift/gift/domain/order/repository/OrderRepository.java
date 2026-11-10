package com.gift.gift.domain.order.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderId(String orderId);

    Optional<Order> findByOrderIdAndPayerId(String orderId, Long payerId);

    Optional<Order> findByPayerIdAndIdempotencyKey(Long payerId, String idempotencyKey);

    List<Order> findAllByPayerIdAndStatusOrderByCreatedAtDescIdDesc(Long payerId, OrderStatus status);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Order orders
            set orders.approvalResult = com.gift.gift.domain.order.entity.PaymentApprovalResult.UNKNOWN,
                orders.reconciliationStartedAt = coalesce(orders.reconciliationStartedAt, :updatedAt),
                orders.nextReconciliationAt = :nextReconciliationAt,
                orders.updatedAt = :updatedAt
            where orders.id = :orderRecordId
              and orders.status = com.gift.gift.domain.order.entity.OrderStatus.PENDING
              and orders.approvalResult is null
            """)
    int claimApproval(
            @Param("orderRecordId") Long orderRecordId,
            @Param("updatedAt") LocalDateTime updatedAt,
            @Param("nextReconciliationAt") LocalDateTime nextReconciliationAt
    );

    default int claimApproval(Long orderRecordId, LocalDateTime updatedAt) {
        return claimApproval(orderRecordId, updatedAt, updatedAt.plusSeconds(10));
    }

    @Query("""
            select orders
            from Order orders
            where orders.status = com.gift.gift.domain.order.entity.OrderStatus.PENDING
              and (
                    (
                        orders.approvalResult =
                            com.gift.gift.domain.order.entity.PaymentApprovalResult.UNKNOWN
                        and orders.nextReconciliationAt is not null
                        and orders.nextReconciliationAt <= :now
                    )
                    or (
                        orders.approvalResult is null
                        and orders.createdAt <= :unclaimedBefore
                    )
              )
              and (
                    orders.failureCode is null
                    or (
                        orders.failureCode <>
                            com.gift.gift.domain.order.entity.OrderFailureCode.PAYMENT_RECONCILIATION_REQUIRED
                        and orders.failureCode <>
                            com.gift.gift.domain.order.entity.OrderFailureCode.AUTO_CANCELLATION_RECONCILIATION_REQUIRED
                    )
              )
            order by orders.nextReconciliationAt asc, orders.createdAt asc, orders.id asc
            """)
    List<Order> findDueUnknownPayments(
            @Param("now") LocalDateTime now,
            @Param("unclaimedBefore") LocalDateTime unclaimedBefore,
            Pageable pageable
    );

    @Query("""
            select orders
            from Order orders
            where orders.status = :pendingStatus
              and orders.approvalResult = :approvedResult
              and orders.updatedAt <= :staleBefore
              and (
                    orders.failureCode is null
                    or (
                        orders.failureCode <> :reconciliationRequired
                        and orders.failureCode <> :autoCancellationRequired
                    )
              )
            order by orders.updatedAt asc, orders.id asc
            """)
    List<Order> findStaleApprovedPayments(
            @Param("pendingStatus") OrderStatus pendingStatus,
            @Param("approvedResult") PaymentApprovalResult approvedResult,
            @Param("reconciliationRequired") OrderFailureCode reconciliationRequired,
            @Param("autoCancellationRequired") OrderFailureCode autoCancellationRequired,
            @Param("staleBefore") LocalDateTime staleBefore,
            Pageable pageable
    );

    @Query("""
            select orders
            from Order orders
            where orders.status = :pendingStatus
              and orders.updatedAt <= :staleBefore
              and (
                    orders.approvalResult is null
                    or orders.approvalResult = :unknownResult
                    or orders.approvalResult = :approvedResult
              )
              and (
                    orders.failureCode is null
                    or (
                        orders.failureCode <> :reconciliationRequired
                        and orders.failureCode <> :autoCancellationRequired
                    )
              )
            order by orders.updatedAt asc, orders.id asc
            """)
    List<Order> findReconciliationCandidates(
            @Param("pendingStatus") OrderStatus pendingStatus,
            @Param("unknownResult") PaymentApprovalResult unknownResult,
            @Param("approvedResult") PaymentApprovalResult approvedResult,
            @Param("reconciliationRequired") OrderFailureCode reconciliationRequired,
            @Param("autoCancellationRequired") OrderFailureCode autoCancellationRequired,
            @Param("staleBefore") LocalDateTime staleBefore,
            Pageable pageable
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update Order orders
            set orders.paymentKey = :paymentKey,
                orders.approvedTransactionKey = :transactionKey,
                orders.approvalResult = :approvalResult,
                orders.approvedAt = :approvedAt,
                orders.failureCode = null,
                orders.nextReconciliationAt = null,
                orders.updatedAt = :updatedAt
            where orders.id = :orderRecordId
              and orders.status = :pendingStatus
              and (
                    orders.approvalResult is null
                    or orders.approvalResult = :unknownResult
              )
            """)
    int recordApproval(
            @Param("orderRecordId") Long orderRecordId,
            @Param("paymentKey") String paymentKey,
            @Param("transactionKey") String transactionKey,
            @Param("approvalResult") PaymentApprovalResult approvalResult,
            @Param("unknownResult") PaymentApprovalResult unknownResult,
            @Param("approvedAt") LocalDateTime approvedAt,
            @Param("updatedAt") LocalDateTime updatedAt,
            @Param("pendingStatus") OrderStatus pendingStatus
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update Order orders
            set orders.status = :completedStatus,
                orders.failureCode = null,
                orders.nextReconciliationAt = null,
                orders.updatedAt = :completedAt
            where orders.id = :orderRecordId
              and orders.status = :pendingStatus
              and orders.approvalResult = :approvedResult
              and (
                    orders.failureCode is null
                    or orders.failureCode <> :blockedFailureCode
              )
            """)
    int complete(
            @Param("orderRecordId") Long orderRecordId,
            @Param("pendingStatus") OrderStatus pendingStatus,
            @Param("completedStatus") OrderStatus completedStatus,
            @Param("approvedResult") PaymentApprovalResult approvedResult,
            @Param("blockedFailureCode") OrderFailureCode blockedFailureCode,
            @Param("completedAt") LocalDateTime completedAt
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
        update Order orders
        set orders.status = :declinedStatus,
            orders.approvalResult = :declinedResult,
            orders.failureCode = :failureCode,
            orders.nextReconciliationAt = null,
            orders.updatedAt = :declinedAt
        where orders.id = :orderRecordId
          and orders.status = :pendingStatus
          and (
                orders.approvalResult is null
                or orders.approvalResult =
                    com.gift.gift.domain.order.entity.PaymentApprovalResult.UNKNOWN
          )
        """)
    int decline(
            @Param("orderRecordId") Long orderRecordId,
            @Param("pendingStatus") OrderStatus pendingStatus,
            @Param("declinedStatus") OrderStatus declinedStatus,
            @Param("declinedResult") PaymentApprovalResult declinedResult,
            @Param("failureCode") OrderFailureCode failureCode,
            @Param("declinedAt") LocalDateTime declinedAt
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update Order orders
            set orders.approvalResult = :unknownResult,
                orders.failureCode = :failureCode,
                orders.reconciliationStartedAt = coalesce(orders.reconciliationStartedAt, :updatedAt),
                orders.nextReconciliationAt = :nextReconciliationAt,
                orders.updatedAt = :updatedAt
            where orders.id = :orderRecordId
              and orders.status = :pendingStatus
              and (
                    orders.approvalResult is null
                    or orders.approvalResult = :unknownResult
              )
            """)
    int markUnknown(
            @Param("orderRecordId") Long orderRecordId,
            @Param("pendingStatus") OrderStatus pendingStatus,
            @Param("unknownResult") PaymentApprovalResult unknownResult,
            @Param("failureCode") OrderFailureCode failureCode,
            @Param("updatedAt") LocalDateTime updatedAt,
            @Param("nextReconciliationAt") LocalDateTime nextReconciliationAt
    );

    default int markUnknown(
            Long orderRecordId,
            OrderStatus pendingStatus,
            PaymentApprovalResult unknownResult,
            OrderFailureCode failureCode,
            LocalDateTime updatedAt
    ) {
        return markUnknown(
                orderRecordId,
                pendingStatus,
                unknownResult,
                failureCode,
                updatedAt,
                updatedAt.plusSeconds(5)
        );
    }

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update Order orders
            set orders.failureCode = :failureCode,
                orders.updatedAt = :updatedAt
            where orders.id = :orderRecordId
              and orders.status = :pendingStatus
              and orders.approvalResult = :approvedResult
            """)
    int markLocalFailure(
            @Param("orderRecordId") Long orderRecordId,
            @Param("pendingStatus") OrderStatus pendingStatus,
            @Param("approvedResult") PaymentApprovalResult approvedResult,
            @Param("failureCode") OrderFailureCode failureCode,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Order orders
            set orders.approvalResult = :unknownResult,
                orders.failureCode = :reconciliationRequired,
                orders.nextReconciliationAt = null,
                orders.updatedAt = :updatedAt
            where orders.id = :orderRecordId
              and orders.status = :pendingStatus
              and (
                    orders.approvalResult is null
                    or orders.approvalResult = :unknownResult
              )
              and (
                    orders.failureCode is null
                    or orders.failureCode <> :reconciliationRequired
              )
            """)
    int markReconciliationRequired(
            @Param("orderRecordId") Long orderRecordId,
            @Param("pendingStatus") OrderStatus pendingStatus,
            @Param("unknownResult") PaymentApprovalResult unknownResult,
            @Param("reconciliationRequired") OrderFailureCode reconciliationRequired,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
        update Order orders
        set orders.status = :canceledStatus,
            orders.failureCode = null,
            orders.nextReconciliationAt = null,
            orders.canceledAt = :canceledAt,
            orders.updatedAt = :canceledAt
        where orders.id = :orderRecordId
          and orders.status = :completedStatus
        """)
    int cancel(
            @Param("orderRecordId") Long orderRecordId,
            @Param("completedStatus") OrderStatus completedStatus,
            @Param("canceledStatus") OrderStatus canceledStatus,
            @Param("canceledAt") LocalDateTime canceledAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Order orders
            set orders.status = :canceledStatus,
                orders.failureCode = :failureCode,
                orders.nextReconciliationAt = null,
                orders.canceledAt = :canceledAt,
                orders.updatedAt = :canceledAt
            where orders.id = :orderRecordId
              and orders.status = :pendingStatus
              and orders.approvalResult = :approvedResult
            """)
    int cancelApproved(
            @Param("orderRecordId") Long orderRecordId,
            @Param("pendingStatus") OrderStatus pendingStatus,
            @Param("canceledStatus") OrderStatus canceledStatus,
            @Param("approvedResult") PaymentApprovalResult approvedResult,
            @Param("failureCode") OrderFailureCode failureCode,
            @Param("canceledAt") LocalDateTime canceledAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Order orders
            set orders.failureCode = :processingCode,
                orders.updatedAt = :updatedAt
            where orders.id = :orderRecordId
              and orders.status = :pendingStatus
              and orders.approvalResult = :approvedResult
              and (
                    orders.failureCode is null
                    or orders.failureCode <> :processingCode
              )
            """)
    int markAutoCancellationProcessing(
            @Param("orderRecordId") Long orderRecordId,
            @Param("pendingStatus") OrderStatus pendingStatus,
            @Param("approvedResult") PaymentApprovalResult approvedResult,
            @Param("processingCode") OrderFailureCode processingCode,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Order orders
            set orders.failureCode = :failureCode,
                orders.updatedAt = :updatedAt
            where orders.id = :orderRecordId
              and orders.status = :pendingStatus
              and orders.approvalResult = :approvedResult
            """)
    int markAutoCancellationRequired(
            @Param("orderRecordId") Long orderRecordId,
            @Param("pendingStatus") OrderStatus pendingStatus,
            @Param("approvedResult") PaymentApprovalResult approvedResult,
            @Param("failureCode") OrderFailureCode failureCode,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Order orders
            set orders.failureCode = :failureCode,
                orders.nextReconciliationAt = :nextReconciliationAt,
                orders.reconciliationAttempts = orders.reconciliationAttempts + 1,
                orders.updatedAt = :updatedAt
            where orders.id = :orderRecordId
              and orders.status = :pendingStatus
              and orders.approvalResult = :unknownResult
              and (
                    orders.failureCode is null
                    or orders.failureCode <> :reconciliationRequired
              )
            """)
    int scheduleUnknownReconciliation(
            @Param("orderRecordId") Long orderRecordId,
            @Param("pendingStatus") OrderStatus pendingStatus,
            @Param("unknownResult") PaymentApprovalResult unknownResult,
            @Param("reconciliationRequired") OrderFailureCode reconciliationRequired,
            @Param("failureCode") OrderFailureCode failureCode,
            @Param("updatedAt") LocalDateTime updatedAt,
            @Param("nextReconciliationAt") LocalDateTime nextReconciliationAt
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select orders
        from Order orders
        where orders.id = :orderRecordId
        """)
    Optional<Order> findByIdForUpdate(
            @Param("orderRecordId") Long orderRecordId
    );
}
