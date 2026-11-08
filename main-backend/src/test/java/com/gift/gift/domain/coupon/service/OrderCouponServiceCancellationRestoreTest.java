package com.gift.gift.domain.coupon.service;

import java.time.Clock;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gift.gift.domain.coupon.entity.OrderCouponUsage;
import com.gift.gift.domain.coupon.entity.OrderCouponUsageStatus;
import com.gift.gift.domain.coupon.repository.CouponInventoryRepository;
import com.gift.gift.domain.coupon.repository.OrderCouponUsageRepository;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.repository.OrderRepository;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCouponServiceCancellationRestoreTest {

    private static final long ORDER_RECORD_ID = 202L;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CouponInventoryRepository inventoryRepository;

    @Mock
    private OrderCouponUsageRepository usageRepository;

    @Mock
    private GiftHistoryRepository giftHistoryRepository;

    @Mock
    private Order order;

    @Mock
    private GiftHistory gift;

    @Mock
    private OrderCouponUsage usage;

    private OrderCouponService orderCouponService;

    @BeforeEach
    void setUp() {
        orderCouponService = new OrderCouponService(
                orderRepository,
                inventoryRepository,
                usageRepository,
                giftHistoryRepository,
                Clock.systemUTC()
        );

        when(orderRepository.findByIdForUpdate(ORDER_RECORD_ID))
                .thenReturn(Optional.of(order));
        when(order.getStatus()).thenReturn(OrderStatus.CANCELED);
        when(giftHistoryRepository.findByOrderRecordId(ORDER_RECORD_ID))
                .thenReturn(Optional.of(gift));
        when(gift.getStatus()).thenReturn(GiftStatus.CANCELED);
    }

    @Test
    @DisplayName("쿠폰 사용 이력이 없는 주문은 수량을 변경하지 않는다")
    void restoreAfterCancellation_withoutCouponUsage_doesNotChangeInventory() {
        when(usageRepository.findByOrderRecordId(ORDER_RECORD_ID))
                .thenReturn(Optional.empty());

        orderCouponService.restoreAfterCancellation(ORDER_RECORD_ID);

        verifyNoInteractions(inventoryRepository);
    }

    @Test
    @DisplayName("이미 복구된 쿠폰은 다시 수량을 증가시키지 않는다")
    void restoreAfterCancellation_alreadyRestored_doesNotIncreaseInventoryAgain() {
        when(usageRepository.findByOrderRecordId(ORDER_RECORD_ID))
                .thenReturn(Optional.of(usage));
        when(usage.getStatus()).thenReturn(OrderCouponUsageStatus.RESTORED);

        orderCouponService.restoreAfterCancellation(ORDER_RECORD_ID);

        verify(inventoryRepository, never()).restoreQuantity(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(usageRepository, never()).restoreUsed(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any()
        );
    }
}
