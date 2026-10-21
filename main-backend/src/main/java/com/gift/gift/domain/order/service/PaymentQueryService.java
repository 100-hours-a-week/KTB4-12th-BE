package com.gift.gift.domain.order.service;

import java.time.Clock;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.dto.response.PaymentStatusResponse;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.exception.OrderErrorCode;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.domain.order.repository.OrderRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentQueryService {

    private final OrderRepository orderRepository;
    private final GiftHistoryRepository giftHistoryRepository;
    private final Clock clock;

    public PaymentStatusResponse getStatus(Long payerId, String orderId) {

        Order order = orderRepository
                .findByOrderIdAndPayerId(orderId, payerId)
                .orElseThrow(() -> new OrderException(OrderErrorCode.PAYMENT_NOT_FOUND));

        GiftHistory gift = giftHistoryRepository
                .findByOrderRecordId(order.getId())
                .orElse(null);

        return PaymentStatusResponse.from(order, gift, clock.getZone());
    }
}
