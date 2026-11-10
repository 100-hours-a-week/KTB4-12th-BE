package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.dto.response.PaymentStatusResponse;
import com.gift.gift.domain.order.dto.response.PendingPaymentOrderResponse;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.order.exception.OrderErrorCode;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PaymentQueryService {

    private final OrderRepository orderRepository;
    private final GiftHistoryRepository giftHistoryRepository;
    private final PaymentCancellationRepository cancellationRepository;
    private final OrderRewardQueryService orderRewardQueryService;
    private final Clock clock;

    public List<PendingPaymentOrderResponse> getPendingPayments(Long payerId) {
        return orderRepository
                .findAllByPayerIdAndStatusOrderByCreatedAtDescIdDesc(
                        payerId,
                        OrderStatus.PENDING
                )
                .stream()
                .map(PendingPaymentOrderResponse::from)
                .toList();
    }

    public PaymentStatusResponse getStatus(Long payerId, String orderId) {
        Order order = orderRepository
                .findByOrderIdAndPayerId(orderId, payerId)
                .orElseThrow(() -> new OrderException(OrderErrorCode.PAYMENT_NOT_FOUND));

        GiftHistory gift = giftHistoryRepository
                .findByOrderRecordId(order.getId())
                .orElse(null);

        PaymentCancellation cancellation = cancellationRepository
                .findFirstByOrderRecordIdAndStatusOrderByUpdatedAtDescIdDesc(
                        order.getId(),
                        PaymentCancellationStatus.PENDING
                )
                .orElseGet(() -> cancellationRepository
                        .findFirstByOrderRecordIdOrderByUpdatedAtDescIdDesc(order.getId())
                        .orElse(null));

        return PaymentStatusResponse.from(
                order,
                gift,
                orderRewardQueryService.summarize(order, gift),
                clock.getZone(),
                cancellation
        );
    }
}
