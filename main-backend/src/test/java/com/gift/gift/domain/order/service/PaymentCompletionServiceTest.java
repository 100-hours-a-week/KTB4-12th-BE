package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.context.ApplicationEventPublisher;

import com.gift.gift.domain.coupon.service.OrderCouponService;
import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.entity.CreditTransaction;
import com.gift.gift.domain.credit.repository.CreditAccountRepository;
import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.event.PaymentReceiptEmailEvent;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.service.ProductQueryService;
import com.gift.gift.domain.product.service.ProductStockService;
import com.gift.gift.domain.roulette.service.RouletteService;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.service.UserQueryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentCompletionServiceTest {

    private static final Long ORDER_RECORD_ID = 10L;
    private static final Long PAYER_ID = 20L;
    private static final Long RECIPIENT_ID = 30L;
    private static final Long PRODUCT_ID = 40L;
    private static final Long GIFT_ID = 50L;
    private static final int QUANTITY = 2;
    private static final long CREDIT_AMOUNT = 40_000L;
    private static final long BALANCE_AFTER = 60_000L;
    private static final String ORDER_ID =
            "00000000-0000-0000-0000-000000000123";
    private static final String REQUEST_HASH = "a".repeat(64);
    private static final LocalDateTime COMPLETED_AT =
            LocalDateTime.of(2026, 10, 24, 12, 30, 45);
    private static final LocalDateTime EMAIL_VERIFIED_AT =
            LocalDateTime.of(2026, 10, 20, 9, 0);

    @Mock
    private OrderCouponService orderCouponService;

    @Mock
    private OrderRewardQueryService orderRewardQueryService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductStockService productStockService;

    @Mock
    private ProductQueryService productQueryService;

    @Mock
    private CreditAccountRepository creditAccountRepository;

    @Mock
    private CreditTransactionRepository creditTransactionRepository;

    @Mock
    private GiftHistoryRepository giftHistoryRepository;

    @Mock
    private UserQueryService userQueryService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private Order pendingOrder;

    @Mock
    private Order completedOrder;

    @Mock
    private Product product;

    @Mock
    private CreditAccount account;

    @Mock
    private User sender;

    @Mock
    private User recipient;

    @Mock
    private GiftHistory savedGift;

    @Mock
    private CreditTransaction savedCreditTransaction;

    @Mock
    private RouletteService rouletteService;

    private PaymentCompletionService paymentCompletionService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                COMPLETED_AT.toInstant(ZoneOffset.UTC),
                ZoneOffset.UTC
        );

        paymentCompletionService = new PaymentCompletionService(
                orderRepository,
                productStockService,
                productQueryService,
                creditAccountRepository,
                creditTransactionRepository,
                giftHistoryRepository,
                userQueryService,
                eventPublisher,
                orderCouponService,
                orderRewardQueryService,
                clock,
                rouletteService
        );

        when(completedOrder.getId()).thenReturn(ORDER_RECORD_ID);
        when(completedOrder.getOrderId()).thenReturn(ORDER_ID);
        when(completedOrder.getPaymentKey()).thenReturn("payment-key");
        when(completedOrder.getApprovedTransactionKey())
                .thenReturn("transaction-key");
        when(completedOrder.getStatus()).thenReturn(OrderStatus.COMPLETED);
        when(completedOrder.getAmount()).thenReturn(CREDIT_AMOUNT);
        when(completedOrder.getApprovedAt()).thenReturn(COMPLETED_AT);

        when(savedGift.getId()).thenReturn(GIFT_ID);
        when(savedGift.getStatus()).thenReturn(GiftStatus.WAITING_FOR_ACCEPTANCE);
        when(savedGift.getExpiresAt()).thenReturn(COMPLETED_AT.plusDays(7));
    }

    @Test
    @DisplayName("결제 완료 시 이메일 인증을 마친 결제자에게 영수증 이벤트를 발행한다")
    void complete_publishesReceiptEventForVerifiedPayer() {
        givenSuccessfulCompletion();
        when(sender.getEmail()).thenReturn("payer@example.com");
        when(sender.getEmailVerifiedAt()).thenReturn(EMAIL_VERIFIED_AT);

        paymentCompletionService.complete(ORDER_RECORD_ID);

        ArgumentCaptor<Object> eventCaptor =
                ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());

        List<PaymentReceiptEmailEvent> receiptEvents =
                eventCaptor.getAllValues().stream()
                        .filter(PaymentReceiptEmailEvent.class::isInstance)
                        .map(PaymentReceiptEmailEvent.class::cast)
                        .toList();

        assertThat(receiptEvents).containsExactly(
                new PaymentReceiptEmailEvent(
                        "payer@example.com",
                        ORDER_ID,
                        "테스트 선물",
                        QUANTITY,
                        CREDIT_AMOUNT,
                        BALANCE_AFTER,
                        COMPLETED_AT
                )
        );

        verify(creditTransactionRepository)
                .saveAndFlush(any(CreditTransaction.class));
    }

    @Test
    @DisplayName("이메일 미인증 결제자는 결제는 완료하되 영수증 이벤트를 발행하지 않는다")
    void complete_doesNotPublishReceiptEventForUnverifiedPayer() {
        givenSuccessfulCompletion();
        when(sender.getEmailVerifiedAt()).thenReturn(null);

        paymentCompletionService.complete(ORDER_RECORD_ID);

        verify(eventPublisher, never())
                .publishEvent(isA(PaymentReceiptEmailEvent.class));
        verify(creditTransactionRepository)
                .saveAndFlush(any(CreditTransaction.class));
    }

    @Test
    @DisplayName("이미 완료된 주문의 재요청은 영수증 이벤트를 다시 발행하지 않는다")
    void complete_doesNotRepublishReceiptForCompletedOrder() {
        when(orderRepository.findByIdForUpdate(ORDER_RECORD_ID))
                .thenReturn(Optional.of(completedOrder));
        givenRestoreData();

        paymentCompletionService.complete(ORDER_RECORD_ID);

        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(productStockService, creditAccountRepository);
    }

    @Test
    @DisplayName("다른 인스턴스가 먼저 주문 완료에 성공한 경우 영수증 이벤트를 발행하지 않는다")
    void complete_doesNotPublishReceiptWhenConcurrentCompletionAlreadyWon() {
        when(orderRepository.findByIdForUpdate(ORDER_RECORD_ID))
                .thenReturn(Optional.of(pendingOrder));
        when(orderRepository.findById(ORDER_RECORD_ID))
                .thenReturn(Optional.of(completedOrder));
        when(pendingOrder.getStatus()).thenReturn(OrderStatus.PENDING);
        when(pendingOrder.getApprovalResult())
                .thenReturn(PaymentApprovalResult.APPROVED);
        when(orderRepository.complete(
                eq(ORDER_RECORD_ID),
                eq(OrderStatus.PENDING),
                eq(OrderStatus.COMPLETED),
                eq(PaymentApprovalResult.APPROVED),
                eq(OrderFailureCode.AUTO_CANCELLATION_PROCESSING),
                any(LocalDateTime.class)
        )).thenReturn(0);
        givenRestoreData();

        paymentCompletionService.complete(ORDER_RECORD_ID);

        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(productStockService, creditAccountRepository);
    }

    private void givenSuccessfulCompletion() {
        when(orderRepository.findByIdForUpdate(ORDER_RECORD_ID))
                .thenReturn(Optional.of(pendingOrder));
        when(orderRepository.findById(ORDER_RECORD_ID))
                .thenReturn(Optional.of(completedOrder));

        when(pendingOrder.getId()).thenReturn(ORDER_RECORD_ID);
        when(pendingOrder.getOrderId()).thenReturn(ORDER_ID);
        when(pendingOrder.getPayerId()).thenReturn(PAYER_ID);
        when(pendingOrder.getRecipientId()).thenReturn(RECIPIENT_ID);
        when(pendingOrder.getProductId()).thenReturn(PRODUCT_ID);
        when(pendingOrder.getQuantity()).thenReturn(QUANTITY);
        when(pendingOrder.getUnitPrice()).thenReturn(20_000L);
        when(pendingOrder.getAmount()).thenReturn(CREDIT_AMOUNT);
        when(pendingOrder.getRequestHash()).thenReturn(REQUEST_HASH);
        when(pendingOrder.getStatus()).thenReturn(OrderStatus.PENDING);
        when(pendingOrder.getApprovalResult())
                .thenReturn(PaymentApprovalResult.APPROVED);

        when(orderRepository.complete(
                eq(ORDER_RECORD_ID),
                eq(OrderStatus.PENDING),
                eq(OrderStatus.COMPLETED),
                eq(PaymentApprovalResult.APPROVED),
                eq(OrderFailureCode.AUTO_CANCELLATION_PROCESSING),
                any(LocalDateTime.class)
        )).thenReturn(1);

        when(productStockService.deductStockIfAvailable(PRODUCT_ID, QUANTITY))
                .thenReturn(true);
        when(creditAccountRepository.decreaseBalance(PAYER_ID, CREDIT_AMOUNT))
                .thenReturn(1);
        when(productQueryService.findAvailableProduct(PRODUCT_ID))
                .thenReturn(Optional.of(product));
        when(creditAccountRepository.findByUserId(PAYER_ID))
                .thenReturn(Optional.of(account));
        when(userQueryService.getReference(PAYER_ID)).thenReturn(sender);
        when(userQueryService.getReference(RECIPIENT_ID)).thenReturn(recipient);
        when(product.getName()).thenReturn("테스트 선물");

        when(completedOrder.getPayerId()).thenReturn(PAYER_ID);
        when(completedOrder.getRecipientId()).thenReturn(RECIPIENT_ID);
        when(savedGift.getProductNameSnapshot()).thenReturn("테스트 선물");
        when(account.getBalance()).thenReturn(BALANCE_AFTER);

        doReturn(savedGift)
                .when(giftHistoryRepository)
                .saveAndFlush(any(GiftHistory.class));
    }

    private void givenRestoreData() {
        when(giftHistoryRepository.findByOrderRecordId(ORDER_RECORD_ID))
                .thenReturn(Optional.of(savedGift));
        when(creditTransactionRepository.findByDeduplicationKey(
                "PAYMENT_USE:" + ORDER_RECORD_ID
        )).thenReturn(Optional.of(savedCreditTransaction));
        when(savedCreditTransaction.getBalanceAfter())
                .thenReturn(BALANCE_AFTER);
    }
}
