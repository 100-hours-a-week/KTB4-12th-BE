package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.coupon.service.OrderCouponService;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.repository.OrderRepository;

@Service
@RequiredArgsConstructor
public class OrderApprovalTransactionService {

    private final OrderRepository orderRepository;
    private final OrderCouponService orderCouponService;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long orderRecordId) {
        return orderRepository.claimApproval(orderRecordId, LocalDateTime.now(clock)) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            Long orderRecordId,
            String paymentKey,
            String transactionKey,
            LocalDateTime approvedAt
    ) {
        LocalDateTime updatedAt = LocalDateTime.now(clock);

        int updated = orderRepository.recordApproval(
                orderRecordId,
                paymentKey,
                transactionKey,
                PaymentApprovalResult.APPROVED,
                PaymentApprovalResult.UNKNOWN,
                approvedAt,
                updatedAt,
                OrderStatus.PENDING
        );

        if (updated == 1) {
            return;
        }

        Order order = findOrder(orderRecordId);

        boolean sameApproval =
                order.getApprovalResult() == PaymentApprovalResult.APPROVED
                        && Objects.equals(order.getPaymentKey(), paymentKey)
                        && Objects.equals(order.getApprovedTransactionKey(), transactionKey)
                        && Objects.equals(order.getApprovedAt(), approvedAt);

        if (!sameApproval) {
            throw new IllegalStateException("기존 주문의 승인 결과와 Payment Server 응답이 일치하지 않습니다.");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void decline(Long orderRecordId) {
        LocalDateTime declinedAt = LocalDateTime.now(clock);

        int updated = orderRepository.decline(
                orderRecordId,
                OrderStatus.PENDING,
                OrderStatus.DECLINED,
                PaymentApprovalResult.DECLINED,
                OrderFailureCode.PAYMENT_DECLINED,
                declinedAt
        );

        if (updated == 0) {
            Order order = findOrder(orderRecordId);

            if (order.getStatus() != OrderStatus.DECLINED) {
                throw new IllegalStateException(
                        "결제 거절 결과를 주문에 반영할 수 없습니다."
                );
            }
        }

        orderCouponService.restoreAfterFailure(orderRecordId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUnknown(Long orderRecordId, OrderFailureCode failureCode) {

        if (failureCode != OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED
                && failureCode != OrderFailureCode.PAYMENT_SERVER_UNAVAILABLE) {
            throw new IllegalArgumentException("승인 미확정 상태에 사용할 수 없는 실패 코드입니다.");
        }

        orderRepository.markUnknown(
                orderRecordId,
                OrderStatus.PENDING,
                PaymentApprovalResult.UNKNOWN,
                failureCode,
                LocalDateTime.now(clock)
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markLocalFailure(Long orderRecordId) {
        orderRepository.markLocalFailure(
                orderRecordId,
                OrderStatus.PENDING,
                PaymentApprovalResult.APPROVED,
                OrderFailureCode.LOCAL_PROCESSING_FAILED,
                LocalDateTime.now(clock)
        );
    }

    private Order findOrder(Long orderRecordId) {
        return orderRepository.findById(orderRecordId)
                .orElseThrow(() -> new IllegalStateException("주문을 찾을 수 없습니다."));
    }
}
