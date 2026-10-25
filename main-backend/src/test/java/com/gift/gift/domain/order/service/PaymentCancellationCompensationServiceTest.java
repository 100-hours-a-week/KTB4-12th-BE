package com.gift.gift.domain.order.service;

import java.time.LocalDateTime;
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

import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.entity.CreditTransaction;
import com.gift.gift.domain.credit.repository.CreditAccountRepository;
import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.order.event.PaymentRefundReceiptEmailEvent;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;
import com.gift.gift.domain.product.service.ProductStockService;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
class PaymentCancellationCompensationServiceTest {

    private static final Long CANCELLATION_ID = 5L;
    private static final Long ORDER_RECORD_ID = 10L;
    private static final Long PAYER_ID = 20L;
    private static final Long GIFT_ID = 30L;
    private static final Long PRODUCT_ID = 40L;
    private static final int QUANTITY = 2;
    private static final long CREDIT_AMOUNT = 40_000L;
    private static final long BALANCE_AFTER = 100_000L;
    private static final String ORDER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String PAYMENT_KEY = "7d9f6d7e-a34c-4bbc-97d6-33a2b43c183a";
    private static final String TRANSACTION_KEY = "542f1507-e235-47c2-93c1-a30ed5b31d49";
    private static final LocalDateTime CANCELED_AT = LocalDateTime.of(2026, 10, 24, 14, 0);
    private static final LocalDateTime EMAIL_VERIFIED_AT = LocalDateTime.of(2026, 10, 20, 9, 0);

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentCancellationRepository cancellationRepository;

    @Mock
    private GiftHistoryRepository giftHistoryRepository;

    @Mock
    private CreditAccountRepository creditAccountRepository;

    @Mock
    private CreditTransactionRepository creditTransactionRepository;

    @Mock
    private ProductStockService productStockService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private PaymentCancellation pendingCancellation;

    @Mock
    private PaymentCancellation completedCancellation;

    @Mock
    private Order completedOrder;

    @Mock
    private Order canceledOrder;

    @Mock
    private GiftHistory gift;

    @Mock
    private User sender;

    @Mock
    private CreditAccount account;

    @Mock
    private CreditTransaction refundTransaction;

    private PaymentCancellationCompensationService service;

    @BeforeEach
    void setUp() {
        service = new PaymentCancellationCompensationService(
                orderRepository,
                cancellationRepository,
                giftHistoryRepository,
                creditAccountRepository,
                creditTransactionRepository,
                productStockService,
                eventPublisher
        );
    }

    @Test
    @DisplayName("취소와 크레딧 복구가 성공하면 인증된 결제자에게 영수증 이벤트를 발행한다")
    void compensate_publishesReceiptEventForVerifiedPayer() {
        PaymentCancellationApproval approval = givenSuccessfulCompensation(true);

        PaymentCancellationResult result = service.compensate(approval);

        assertThat(result.paymentStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(result.cancelAmount()).isEqualTo(CREDIT_AMOUNT);
        assertThat(result.balanceAfter()).isEqualTo(BALANCE_AFTER);
        assertThat(result.created()).isTrue();

        ArgumentCaptor<Object> eventCaptor =
                ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());

        List<PaymentRefundReceiptEmailEvent> receiptEvents =
                eventCaptor.getAllValues().stream()
                        .filter(PaymentRefundReceiptEmailEvent.class::isInstance)
                        .map(PaymentRefundReceiptEmailEvent.class::cast)
                        .toList();

        assertThat(receiptEvents).containsExactly(
                new PaymentRefundReceiptEmailEvent(
                        "payer@example.com",
                        ORDER_ID,
                        "테스트 선물",
                        QUANTITY,
                        CREDIT_AMOUNT,
                        BALANCE_AFTER,
                        CANCELED_AT
                )
        );

        verify(creditTransactionRepository)
                .saveAndFlush(any(CreditTransaction.class));
    }

    @Test
    @DisplayName("이메일 미인증 결제자의 취소는 완료하되 영수증 이벤트는 발행하지 않는다")
    void compensate_doesNotPublishReceiptEventForUnverifiedPayer() {
        PaymentCancellationApproval approval = givenSuccessfulCompensation(false);

        service.compensate(approval);

        verify(eventPublisher, never())
                .publishEvent(isA(PaymentRefundReceiptEmailEvent.class));
        verify(creditTransactionRepository)
                .saveAndFlush(any(CreditTransaction.class));
    }

    @Test
    @DisplayName("이미 완료된 취소의 재요청은 복구 영수증을 다시 발행하지 않는다")
    void compensate_doesNotRepublishReceiptForCompletedCancellation() {
        givenCompletedCancellationRestore();

        PaymentCancellationApproval approval =
                new PaymentCancellationApproval(
                        completedCancellation,
                        canceledOrder,
                        TRANSACTION_KEY,
                        CREDIT_AMOUNT,
                        CANCELED_AT,
                        true
                );

        PaymentCancellationResult result = service.compensate(approval);

        assertThat(result.paymentStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(result.created()).isFalse();
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(creditAccountRepository, productStockService);
    }

    @Test
    @DisplayName("다른 요청이 선물을 먼저 취소한 경우 영수증 이벤트를 발행하지 않는다")
    void compensate_doesNotPublishReceiptWhenGiftCancellationLosesRace() {
        PaymentCancellationApproval approval = givenGiftCancellationRaceLost();

        assertThatThrownBy(() -> service.compensate(approval))
                .isInstanceOf(OrderException.class);

        verify(eventPublisher, never())
                .publishEvent(isA(PaymentRefundReceiptEmailEvent.class));
        verifyNoInteractions(creditAccountRepository, productStockService);
    }

    private PaymentCancellationApproval givenSuccessfulCompensation(
            boolean emailVerified
    ) {
        when(cancellationRepository.findById(CANCELLATION_ID))
                .thenReturn(
                        Optional.of(pendingCancellation),
                        Optional.of(completedCancellation)
                );
        when(pendingCancellation.getId()).thenReturn(CANCELLATION_ID);
        when(pendingCancellation.getStatus())
                .thenReturn(PaymentCancellationStatus.PENDING);
        when(pendingCancellation.getOrderRecordId()).thenReturn(ORDER_RECORD_ID);

        when(completedCancellation.getOrderRecordId()).thenReturn(ORDER_RECORD_ID);
        when(completedCancellation.getCancelTransactionKey())
                .thenReturn(TRANSACTION_KEY);
        when(completedCancellation.getCancelAmount()).thenReturn(CREDIT_AMOUNT);
        when(completedCancellation.getCanceledAt()).thenReturn(CANCELED_AT);

        when(orderRepository.findById(ORDER_RECORD_ID))
                .thenReturn(
                        Optional.of(completedOrder),
                        Optional.of(canceledOrder)
                );
        when(completedOrder.getId()).thenReturn(ORDER_RECORD_ID);
        when(completedOrder.getStatus()).thenReturn(OrderStatus.COMPLETED);
        when(completedOrder.getAmount()).thenReturn(CREDIT_AMOUNT);
        when(completedOrder.getPayerId()).thenReturn(PAYER_ID);
        when(completedOrder.getProductId()).thenReturn(PRODUCT_ID);
        when(completedOrder.getQuantity()).thenReturn(QUANTITY);

        when(canceledOrder.getId()).thenReturn(ORDER_RECORD_ID);
        when(canceledOrder.getStatus()).thenReturn(OrderStatus.CANCELED);
        when(canceledOrder.getOrderId()).thenReturn(ORDER_ID);
        when(canceledOrder.getPaymentKey()).thenReturn(PAYMENT_KEY);

        when(giftHistoryRepository.findByOrderRecordId(ORDER_RECORD_ID))
                .thenReturn(Optional.of(gift), Optional.of(gift));
        when(gift.getId()).thenReturn(GIFT_ID);
        when(gift.getStatus())
                .thenReturn(GiftStatus.WAITING_FOR_ACCEPTANCE, GiftStatus.CANCELED);
        when(gift.getProductNameSnapshot()).thenReturn("테스트 선물");
        when(gift.getSender()).thenReturn(sender);
        when(sender.getEmailVerifiedAt())
                .thenReturn(emailVerified ? EMAIL_VERIFIED_AT : null);

        if (emailVerified) {
            when(completedOrder.getOrderId()).thenReturn(ORDER_ID);
            when(gift.getQuantity()).thenReturn(QUANTITY);
            when(sender.getEmail()).thenReturn("payer@example.com");
        }

        when(giftHistoryRepository.cancel(GIFT_ID, CANCELED_AT))
                .thenReturn(1);
        when(orderRepository.cancel(
                ORDER_RECORD_ID,
                OrderStatus.COMPLETED,
                OrderStatus.CANCELED,
                CANCELED_AT
        )).thenReturn(1);
        when(creditAccountRepository.increaseBalance(PAYER_ID, CREDIT_AMOUNT))
                .thenReturn(1);
        when(productStockService.restoreStock(PRODUCT_ID, QUANTITY))
                .thenReturn(true);
        when(creditAccountRepository.findByUserId(PAYER_ID))
                .thenReturn(Optional.of(account));
        when(account.getBalance()).thenReturn(BALANCE_AFTER);

        doReturn(refundTransaction)
                .when(creditTransactionRepository)
                .saveAndFlush(any(CreditTransaction.class));
        when(creditTransactionRepository.findByDeduplicationKey(
                "PAYMENT_REFUND:" + ORDER_RECORD_ID
        )).thenReturn(Optional.of(refundTransaction));
        when(refundTransaction.getBalanceAfter()).thenReturn(BALANCE_AFTER);

        when(cancellationRepository.complete(
                eq(CANCELLATION_ID),
                eq(PaymentCancellationStatus.PENDING),
                eq(PaymentCancellationStatus.COMPLETED),
                eq(TRANSACTION_KEY),
                eq(CREDIT_AMOUNT),
                eq(CANCELED_AT)
        )).thenReturn(1);

        return new PaymentCancellationApproval(
                pendingCancellation,
                completedOrder,
                TRANSACTION_KEY,
                CREDIT_AMOUNT,
                CANCELED_AT,
                true
        );
    }

    private void givenCompletedCancellationRestore() {
        when(cancellationRepository.findById(CANCELLATION_ID))
                .thenReturn(Optional.of(completedCancellation));
        when(completedCancellation.getId()).thenReturn(CANCELLATION_ID);
        when(completedCancellation.getStatus())
                .thenReturn(PaymentCancellationStatus.COMPLETED);
        when(completedCancellation.getOrderRecordId()).thenReturn(ORDER_RECORD_ID);
        when(completedCancellation.getCancelTransactionKey())
                .thenReturn(TRANSACTION_KEY);
        when(completedCancellation.getCancelAmount()).thenReturn(CREDIT_AMOUNT);
        when(completedCancellation.getCanceledAt()).thenReturn(CANCELED_AT);

        when(orderRepository.findById(ORDER_RECORD_ID))
                .thenReturn(Optional.of(canceledOrder));
        when(canceledOrder.getId()).thenReturn(ORDER_RECORD_ID);
        when(canceledOrder.getStatus()).thenReturn(OrderStatus.CANCELED);
        when(canceledOrder.getOrderId()).thenReturn(ORDER_ID);
        when(canceledOrder.getPaymentKey()).thenReturn(PAYMENT_KEY);

        when(giftHistoryRepository.findByOrderRecordId(ORDER_RECORD_ID))
                .thenReturn(Optional.of(gift));
        when(gift.getId()).thenReturn(GIFT_ID);
        when(gift.getStatus()).thenReturn(GiftStatus.CANCELED);

        when(creditTransactionRepository.findByDeduplicationKey(
                "PAYMENT_REFUND:" + ORDER_RECORD_ID
        )).thenReturn(Optional.of(refundTransaction));
        when(refundTransaction.getBalanceAfter()).thenReturn(BALANCE_AFTER);
    }

    private PaymentCancellationApproval givenGiftCancellationRaceLost() {
        when(cancellationRepository.findById(CANCELLATION_ID))
                .thenReturn(Optional.of(pendingCancellation));
        when(pendingCancellation.getId()).thenReturn(CANCELLATION_ID);
        when(pendingCancellation.getStatus())
                .thenReturn(PaymentCancellationStatus.PENDING);
        when(pendingCancellation.getOrderRecordId()).thenReturn(ORDER_RECORD_ID);

        when(orderRepository.findById(ORDER_RECORD_ID))
                .thenReturn(Optional.of(completedOrder));
        when(completedOrder.getId()).thenReturn(ORDER_RECORD_ID);
        when(completedOrder.getStatus()).thenReturn(OrderStatus.COMPLETED);
        when(completedOrder.getAmount()).thenReturn(CREDIT_AMOUNT);

        when(giftHistoryRepository.findByOrderRecordId(ORDER_RECORD_ID))
                .thenReturn(Optional.of(gift));
        when(gift.getId()).thenReturn(GIFT_ID);
        when(gift.getStatus()).thenReturn(GiftStatus.WAITING_FOR_ACCEPTANCE);
        when(gift.getSender()).thenReturn(sender);
        when(sender.getEmailVerifiedAt()).thenReturn(EMAIL_VERIFIED_AT);
        when(sender.getEmail()).thenReturn("payer@example.com");

        when(giftHistoryRepository.cancel(GIFT_ID, CANCELED_AT))
                .thenReturn(0);

        return new PaymentCancellationApproval(
                pendingCancellation,
                completedOrder,
                TRANSACTION_KEY,
                CREDIT_AMOUNT,
                CANCELED_AT,
                true
        );
    }
}
