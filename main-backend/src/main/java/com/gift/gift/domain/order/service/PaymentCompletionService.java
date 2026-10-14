package com.gift.gift.domain.order.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.entity.CreditTransaction;
import com.gift.gift.domain.credit.entity.CreditTransactionType;
import com.gift.gift.domain.credit.repository.CreditAccountRepository;
import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.exception.OrderErrorCode;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.service.ProductQueryService;
import com.gift.gift.domain.product.service.ProductStockService;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.service.UserQueryService;

@Service
@RequiredArgsConstructor
public class PaymentCompletionService {

    private static final long GIFT_EXPIRATION_DAYS = 7L;
    private static final String LEDGER_KEY_PREFIX = "PAYMENT_USE:";

    private final OrderRepository orderRepository;
    private final ProductStockService productStockService;
    private final ProductQueryService productQueryService;
    private final CreditAccountRepository creditAccountRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final GiftHistoryRepository giftHistoryRepository;
    private final UserQueryService userQueryService;
    private final Clock clock;

    @Transactional
    public PaymentCompletion complete(Long orderRecordId) {
        Order order = findOrder(orderRecordId);

        if (order.getStatus() == OrderStatus.COMPLETED) {
            return restore(order);
        }

        if (order.getStatus() != OrderStatus.PENDING
                || order.getApprovalResult()
                != PaymentApprovalResult.APPROVED) {
            throw new OrderException(OrderErrorCode.PAYMENT_STATE_CONFLICT);
        }

        LocalDateTime completedAt = LocalDateTime.now(clock);

        int completed = orderRepository.complete(
                orderRecordId,
                OrderStatus.PENDING,
                OrderStatus.COMPLETED,
                PaymentApprovalResult.APPROVED,
                completedAt
        );

        if (completed == 0) {
            return resolveConcurrentCompletion(orderRecordId);
        }

        boolean stockDeducted =
                productStockService.deductStockIfAvailable(
                        order.getProductId(),
                        order.getQuantity()
                );

        if (!stockDeducted) {
            throw productFailure(order.getProductId());
        }

        int balanceUpdated =
                creditAccountRepository.decreaseBalance(
                        order.getPayerId(),
                        order.getAmount()
                );

        if (balanceUpdated == 0) {
            throw new OrderException(
                    OrderErrorCode.INSUFFICIENT_CREDIT
            );
        }

        Product product = productQueryService
                .findAvailableProduct(order.getProductId())
                .orElseThrow(() -> new OrderException(
                        OrderErrorCode.PRODUCT_NOT_FOUND
                ));

        CreditAccount account = creditAccountRepository
                .findByUserId(order.getPayerId())
                .orElseThrow(() -> new IllegalStateException(
                        "결제자의 크레딧 계좌가 존재하지 않습니다."
                ));

        User sender = userQueryService.getReference(
                order.getPayerId()
        );
        User recipient = userQueryService.getReference(
                order.getRecipientId()
        );

        LocalDateTime expiresAt = completedAt.plusDays(
                GIFT_EXPIRATION_DAYS
        );

        GiftHistory gift = GiftHistory.waitingForAcceptance(
                order.getId(),
                sender,
                recipient,
                product,
                order.getQuantity(),
                BigDecimal.valueOf(order.getUnitPrice()),
                product.getName(),
                UUID.fromString(order.getOrderId()),
                order.getRequestHash(),
                expiresAt
        );

        GiftHistory savedGift = giftHistoryRepository.saveAndFlush(gift);

        CreditTransaction transaction = new CreditTransaction(
                order.getPayerId(),
                CreditTransactionType.PAYMENT_USE,
                order.getAmount(),
                account.getBalance(),
                order.getId(),
                savedGift.getId(),
                null,
                ledgerKey(order.getId())
        );

        creditTransactionRepository.saveAndFlush(transaction);

        Order completedOrder = findOrder(orderRecordId);

        return toCompletion(
                completedOrder,
                savedGift,
                account.getBalance()
        );
    }

    @Transactional(readOnly = true)
    public PaymentCompletion findCompleted(Long orderRecordId) {
        Order order = findOrder(orderRecordId);

        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new OrderException(OrderErrorCode.PAYMENT_STATE_CONFLICT);
        }

        return restore(order);
    }

    private PaymentCompletion resolveConcurrentCompletion(
            Long orderRecordId
    ) {
        Order order = findOrder(orderRecordId);

        if (order.getStatus() == OrderStatus.COMPLETED) {
            return restore(order);
        }

        throw new OrderException(
                OrderErrorCode.PAYMENT_STATE_CONFLICT
        );
    }

    private PaymentCompletion restore(Order order) {
        GiftHistory gift = giftHistoryRepository
                .findByOrderRecordId(order.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "완료된 주문의 선물 이력이 존재하지 않습니다."
                ));

        CreditTransaction transaction =
                creditTransactionRepository
                        .findByDeduplicationKey(
                                ledgerKey(order.getId())
                        )
                        .orElseThrow(() -> new IllegalStateException(
                                "완료된 주문의 크레딧 원장이 존재하지 않습니다."
                        ));

        return toCompletion(
                order,
                gift,
                transaction.getBalanceAfter()
        );
    }

    private PaymentCompletion toCompletion(
            Order order,
            GiftHistory gift,
            long balanceAfter
    ) {
        return new PaymentCompletion(
                order.getOrderId(),
                order.getPaymentKey(),
                order.getApprovedTransactionKey(),
                order.getStatus(),
                gift.getId(),
                gift.getStatus(),
                order.getAmount(),
                balanceAfter,
                order.getApprovedAt(),
                gift.getExpiresAt()
        );
    }

    private Order findOrder(Long orderRecordId) {
        return orderRepository.findById(orderRecordId)
                .orElseThrow(() -> new IllegalStateException(
                        "주문을 찾을 수 없습니다."
                ));
    }

    private OrderException productFailure(Long productId) {
        boolean exists = productQueryService
                .findAvailableProduct(productId)
                .isPresent();

        return exists
                ? new OrderException(
                OrderErrorCode.INSUFFICIENT_STOCK
        )
                : new OrderException(
                OrderErrorCode.PRODUCT_NOT_FOUND
        );
    }

    private String ledgerKey(Long orderRecordId) {
        return LEDGER_KEY_PREFIX + orderRecordId;
    }
}
