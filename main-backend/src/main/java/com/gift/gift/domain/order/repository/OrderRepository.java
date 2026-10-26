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
                orders.updatedAt = :updatedAt
            where orders.id = :orderRecordId
              and orders.status = :pendingStatus
              and (
                    orders.approvalResult is null
                    or orders.approvalResult = :unknownResult
              )
            """)
    // Payment Server의 최종 승인 결과를 BE가 확정할 수 없을 때 사용
    // Payment Server -> BE 로 결제 승인 완료 응답 전송 과정에서 타임아웃이나 연결 종료인 경우
    int markUnknown(
            @Param("orderRecordId") Long orderRecordId,
            @Param("pendingStatus") OrderStatus pendingStatus,
            @Param("unknownResult") PaymentApprovalResult unknownResult,
            @Param("failureCode") OrderFailureCode failureCode,
            @Param("updatedAt") LocalDateTime updatedAt
    );

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
    // Payment Server의 결제 승인을 BE가 확인했으나, BE에서 후속 처리 실패했을 때 사용
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
