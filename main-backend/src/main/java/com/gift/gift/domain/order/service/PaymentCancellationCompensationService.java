package com.gift.gift.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.entity.CreditTransaction;
import com.gift.gift.domain.credit.entity.CreditTransactionType;
import com.gift.gift.domain.credit.repository.CreditAccountRepository;
import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.order.event.PaymentCanceledEvent;
import com.gift.gift.domain.order.event.PaymentRefundReceiptEmailEvent;
import com.gift.gift.domain.order.exception.OrderErrorCode;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;
import com.gift.gift.domain.product.service.ProductStockService;
import com.gift.gift.domain.user.entity.User;

@Service
@RequiredArgsConstructor
public class PaymentCancellationCompensationService {

    private static final String LEDGER_KEY_PREFIX = "PAYMENT_REFUND:";

    private final OrderRepository orderRepository;
    private final PaymentCancellationRepository cancellationRepository;
    private final GiftHistoryRepository giftHistoryRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final ProductStockService productStockService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public PaymentCancellationResult compensate(PaymentCancellationApproval approval) {

        PaymentCancellation cancellation = findCancellation(approval.cancellation().getId());

        if (cancellation.getStatus() == PaymentCancellationStatus.COMPLETED) {
            return restore(cancellation, false);
        }

        if (cancellation.getStatus() != PaymentCancellationStatus.PENDING) {
            throw new OrderException(OrderErrorCode.PAYMENT_NOT_CANCELABLE);
        }

        Order order = findOrder(cancellation.getOrderRecordId());

        GiftHistory gift = giftHistoryRepository
                .findByOrderRecordId(order.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "취소할 주문의 선물 이력이 존재하지 않습니다."
                ));

        validate(order, gift, approval);

        // 취소 bulk update가 persistence context를 비우므로 수신 정보를 먼저 읽는다.
        User sender = gift.getSender();
        String recipientEmail = sender.getEmailVerifiedAt() == null
                ? null
                : sender.getEmail();

        int giftCanceled = giftHistoryRepository.cancel(gift.getId(), approval.canceledAt());

        if (giftCanceled != 1) {
            throw new OrderException(OrderErrorCode.PAYMENT_NOT_CANCELABLE);
        }

        int orderCanceled = orderRepository.cancel(
                order.getId(),
                OrderStatus.COMPLETED,
                OrderStatus.CANCELED,
                approval.canceledAt()
        );

        if (orderCanceled != 1) {
            throw new OrderException(OrderErrorCode.PAYMENT_NOT_CANCELABLE);
        }

        int balanceUpdated = creditAccountRepository.increaseBalance(order.getPayerId(), order.getAmount());

        if (balanceUpdated != 1) {
            throw new IllegalStateException("결제자의 크레딧 계좌를 복구할 수 없습니다.");
        }

        boolean stockRestored = productStockService.restoreStock(order.getProductId(), order.getQuantity());

        if (!stockRestored) {
            throw new IllegalStateException("상품 재고와 판매량을 복구할 수 없습니다.");
        }

        CreditAccount account = creditAccountRepository
                .findByUserId(order.getPayerId())
                .orElseThrow(() -> new IllegalStateException("결제자의 크레딧 계좌가 존재하지 않습니다."));

        CreditTransaction refund =
                new CreditTransaction(
                        order.getPayerId(),
                        CreditTransactionType.PAYMENT_REFUND,
                        order.getAmount(),
                        account.getBalance(),
                        order.getId(),
                        gift.getId(),
                        null,
                        ledgerKey(order.getId())
                );

        creditTransactionRepository.saveAndFlush(refund);

        int cancellationCompleted =
                cancellationRepository.complete(
                        cancellation.getId(),
                        PaymentCancellationStatus.PENDING,
                        PaymentCancellationStatus.COMPLETED,
                        approval.transactionKey(),
                        approval.cancelAmount(),
                        approval.canceledAt()
                );

        if (cancellationCompleted != 1) {
            throw new IllegalStateException("결제 취소 완료 상태를 저장할 수 없습니다.");
        }

        PaymentCancellation completedCancellation = findCancellation(cancellation.getId());

        PaymentCancellationResult result =
                restore(
                        completedCancellation,
                        approval.created()
                );

        eventPublisher.publishEvent(
                new PaymentCanceledEvent(
                        order.getId(),
                        order.getPayerId(),
                        gift.getId(),
                        gift.getProductNameSnapshot()
                )
        );

        if (recipientEmail != null) {
            eventPublisher.publishEvent(
                    new PaymentRefundReceiptEmailEvent(
                            recipientEmail,
                            order.getOrderId(),
                            gift.getProductNameSnapshot(),
                            gift.getQuantity(),
                            refund.getAmount(),
                            refund.getBalanceAfter(),
                            approval.canceledAt()
                    )
            );
        }

        return result;
    }

    private void validate(Order order, GiftHistory gift, PaymentCancellationApproval approval) {

        boolean valid = order.getStatus() == OrderStatus.COMPLETED
                && gift.getStatus() == GiftStatus.WAITING_FOR_ACCEPTANCE
                && order.getAmount().equals(approval.cancelAmount());

        if (!valid) {
            throw new OrderException(OrderErrorCode.PAYMENT_NOT_CANCELABLE);
        }
    }

    private PaymentCancellationResult restore(PaymentCancellation cancellation, boolean created) {

        Order order = findOrder(cancellation.getOrderRecordId());

        GiftHistory gift = giftHistoryRepository
                .findByOrderRecordId(order.getId())
                .orElseThrow(() -> new IllegalStateException("취소된 주문의 선물 이력이 존재하지 않습니다."));

        CreditTransaction refund =
                creditTransactionRepository
                        .findByDeduplicationKey(
                                ledgerKey(order.getId())
                        )
                        .orElseThrow(() -> new IllegalStateException(
                                "취소된 주문의 크레딧 반환 원장이 존재하지 않습니다."
                        ));

        if (order.getStatus() != OrderStatus.CANCELED
                || gift.getStatus() != GiftStatus.CANCELED) {
            throw new IllegalStateException("완료된 취소의 주문 또는 선물 상태가 올바르지 않습니다.");
        }

        return new PaymentCancellationResult(
                order.getOrderId(),
                order.getPaymentKey(),
                cancellation.getCancelTransactionKey(),
                order.getStatus(),
                gift.getId(),
                gift.getStatus(),
                cancellation.getCancelAmount(),
                refund.getBalanceAfter(),
                cancellation.getCanceledAt(),
                created
        );
    }

    private Order findOrder(Long orderRecordId) {
        return orderRepository.findById(orderRecordId)
                .orElseThrow(() -> new IllegalStateException("취소할 주문을 찾을 수 없습니다."));
    }

    private PaymentCancellation findCancellation(
            Long cancellationId
    ) {
        return cancellationRepository.findById(cancellationId)
                .orElseThrow(() -> new IllegalStateException("결제 취소 요청을 찾을 수 없습니다."));
    }

    private String ledgerKey(Long orderRecordId) {
        return LEDGER_KEY_PREFIX + orderRecordId;
    }
}
