package com.gift.gift.domain.order.service;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.coupon.service.OrderCouponService;
import com.gift.gift.domain.order.dto.request.PaymentCreateRequest;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class OrderTransactionService {

    private final OrderRepository orderRepository;
    private final OrderCouponService orderCouponService;

    @Value("${app.coupon.payment-enabled:true}")
    private boolean couponPaymentEnabled;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Order create(
            Long payerId,
            String idempotencyKey,
            String requestHash,
            PaymentCreateRequest request,
            PaymentValidation validation
    ) {
        Integer multiplier = request.couponMultiplier();

        if (multiplier != null && multiplier != 2 && multiplier != 3) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        if (multiplier != null && !couponPaymentEnabled) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "쿠폰 결제는 아직 사용할 수 없습니다."
            );
        }

        Order order = new Order(
                UUID.randomUUID().toString(),
                payerId,
                request.recipientUserId(),
                request.productId(),
                request.quantity(),
                validation.unitPrice(),
                validation.amount(),
                idempotencyKey,
                requestHash
        );

        Order savedOrder = orderRepository.saveAndFlush(order);

        if (multiplier != null) {
            orderCouponService.reserve(
                    savedOrder.getId(),
                    multiplier
            );
        }

        return savedOrder;
    }
}
