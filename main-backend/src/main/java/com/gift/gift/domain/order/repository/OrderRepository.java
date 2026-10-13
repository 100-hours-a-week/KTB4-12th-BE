package com.gift.gift.domain.order.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderId(String orderId);

    Optional<Order> findByPayerIdAndIdempotencyKey(Long payerId, String idempotencyKey);

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
              and orders.approvalResult is null
            """)
    int recordApproval(
            @Param("orderRecordId") Long orderRecordId,
            @Param("paymentKey") String paymentKey,
            @Param("transactionKey") String transactionKey,
            @Param("approvalResult") PaymentApprovalResult approvalResult,
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
            """)
    int complete(
            @Param("orderRecordId") Long orderRecordId,
            @Param("pendingStatus") OrderStatus pendingStatus,
            @Param("completedStatus") OrderStatus completedStatus,
            @Param("approvedResult") PaymentApprovalResult approvedResult,
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
}
