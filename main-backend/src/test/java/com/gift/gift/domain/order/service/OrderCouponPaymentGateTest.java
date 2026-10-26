package com.gift.gift.domain.order.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.coupon.service.OrderCouponService;
import com.gift.gift.domain.order.dto.request.PaymentCreateRequest;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderCouponPaymentGateTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderCouponService coupons = mock(OrderCouponService.class);
    private final OrderTransactionService service = new OrderTransactionService(orders, coupons);

    @Test
    @DisplayName("기본 설정에서는 쿠폰 선택 결제를 차단하고 저장하지 않는다")
    void create_rejectsCouponWhenDisabled() {
        assertThatThrownBy(() -> create(2))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        verifyNoInteractions(orders, coupons);
    }

    @Test
    @DisplayName("기본 설정에서도 쿠폰 미사용 주문은 기존대로 생성한다")
    void create_allowsPaymentWithoutCoupon() {
        when(orders.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Order order = create(null);
        assertThat(order.getPayerId()).isEqualTo(1L);
        verifyNoInteractions(coupons);
    }

    @Test
    @DisplayName("내부 호출에서도 2·3 이외 배수는 입력 오류다")
    void create_rejectsInvalidMultiplier() {
        assertThatThrownBy(() -> create(4))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        verifyNoInteractions(orders, coupons);
    }

    private Order create(Integer multiplier) {
        return service.create(1L, "test-key", "a".repeat(64),
                new PaymentCreateRequest(101L, 2L, 1, 1000L, multiplier),
                new PaymentValidation(1000L, 1000L));
    }
}
