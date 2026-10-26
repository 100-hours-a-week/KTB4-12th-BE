package com.gift.gift.domain.gift.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.coupon.service.OrderCouponService;
import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.entity.CreditTransaction;
import com.gift.gift.domain.credit.entity.CreditTransactionType;
import com.gift.gift.domain.credit.repository.CreditAccountRepository;
import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.gift.event.GiftCompletedRewardEvent;
import com.gift.gift.domain.gift.exception.GiftErrorCode;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;

@Service
@RequiredArgsConstructor
public class GiftAcceptanceService {

    private static final long BASE_REWARD_AMOUNT = 50_000L;

    private static final String LEDGER_KEY_PREFIX =
            "GIFT_COMPLETED_REWARD:";

    private final GiftHistoryRepository giftHistoryRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final OrderCouponService orderCouponService;
    private final OrderRepository orderRepository;
    private final PaymentCancellationRepository cancellationRepository;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public GiftAcceptanceResult accept(Long recipientId, Long giftId) {
        GiftHistory gift = giftHistoryRepository
                .findForAcceptance(giftId, recipientId)
                .orElseThrow(() -> new GiftException(
                        GiftErrorCode.RECEIVED_GIFT_NOT_FOUND
                ));

        if (gift.getStatus() == GiftStatus.COMPLETED) {
            return restore(gift);
        }

        validateAcceptable(gift);

        // 취소 요청 저장과 같은 순서로 선물 → 주문을 잠근다.
        Order order = orderRepository
                .findByIdForUpdate(gift.getOrderRecordId())
                .orElseThrow(() -> new IllegalStateException(
                        "선물의 주문이 존재하지 않습니다."
                ));

        if (order.getStatus() != OrderStatus.COMPLETED
                || cancellationRepository.existsByOrderRecordIdAndStatusIn(
                order.getId(),
                List.of(
                        PaymentCancellationStatus.PENDING,
                        PaymentCancellationStatus.FAILED
                )
        )) {
            throw new GiftException(GiftErrorCode.GIFT_STATE_CONFLICT);
        }

        int rewardMultiplier = orderCouponService.rewardMultiplier(
                order.getId(),
                order.getPayerId()
        );

        long rewardAmount = Math.multiplyExact(
                BASE_REWARD_AMOUNT,
                rewardMultiplier
        );

        Long senderId = gift.getSender().getId();
        LocalDateTime completedAt = LocalDateTime.now(clock);

        int completed = giftHistoryRepository.complete(
                giftId,
                recipientId,
                completedAt
        );

        if (completed == 0) {
            return resolveConcurrentAcceptance(giftId, recipientId);
        }

        int balanceUpdated = creditAccountRepository.increaseBalance(
                senderId,
                rewardAmount
        );

        if (balanceUpdated != 1) {
            throw new IllegalStateException(
                    "발신자의 크레딧 계좌에 보상을 지급할 수 없습니다."
            );
        }

        CreditAccount account = creditAccountRepository
                .findByUserId(senderId)
                .orElseThrow(() -> new IllegalStateException(
                        "발신자의 크레딧 계좌가 존재하지 않습니다."
                ));

        CreditTransaction reward = new CreditTransaction(
                senderId,
                CreditTransactionType.GIFT_COMPLETED_REWARD,
                rewardAmount,
                account.getBalance(),
                gift.getOrderRecordId(),
                gift.getId(),
                null,
                ledgerKey(gift.getId(), senderId)
        );

        creditTransactionRepository.saveAndFlush(reward);

        GiftHistory completedGift = findGift(giftId, recipientId);

        eventPublisher.publishEvent(
                new GiftCompletedRewardEvent(
                        completedGift.getId(),
                        senderId,
                        completedGift.getProductNameSnapshot(),
                        reward.getAmount(),
                        rewardMultiplier
                )
        );

        return toResult(completedGift, reward);
    }

    private GiftAcceptanceResult resolveConcurrentAcceptance(
            Long giftId,
            Long recipientId
    ) {
        GiftHistory gift = findGift(giftId, recipientId);

        if (gift.getStatus() == GiftStatus.COMPLETED) {
            return restore(gift);
        }

        throw new GiftException(GiftErrorCode.GIFT_STATE_CONFLICT);
    }

    private GiftAcceptanceResult restore(GiftHistory gift) {
        Long senderId = gift.getSender().getId();

        CreditTransaction reward = creditTransactionRepository
                .findByDeduplicationKey(
                        ledgerKey(gift.getId(), senderId)
                )
                .orElseThrow(() -> new IllegalStateException(
                        "완료된 선물의 보상 원장이 존재하지 않습니다."
                ));

        return toResult(gift, reward);
    }

    private GiftAcceptanceResult toResult(
            GiftHistory gift,
            CreditTransaction reward
    ) {
        if (gift.getStatus() != GiftStatus.COMPLETED
                || gift.getCompletedAt() == null) {
            throw new IllegalStateException(
                    "완료된 선물 상태가 올바르지 않습니다."
            );
        }

        return new GiftAcceptanceResult(
                gift.getId(),
                gift.getStatus(),
                reward.getAmount(),
                gift.getCompletedAt()
        );
    }

    private void validateAcceptable(GiftHistory gift) {
        if (gift.getStatus() != GiftStatus.WAITING_FOR_ACCEPTANCE) {
            throw new GiftException(GiftErrorCode.GIFT_NOT_ACCEPTABLE);
        }

        LocalDateTime now = LocalDateTime.now(clock);

        if (gift.getExpiresAt() == null
                || !gift.getExpiresAt().isAfter(now)) {
            throw new GiftException(GiftErrorCode.GIFT_NOT_ACCEPTABLE);
        }

        if (gift.getOrderRecordId() == null) {
            throw new IllegalStateException(
                    "수락 대기 선물에 연결된 주문이 없습니다."
            );
        }
    }

    private GiftHistory findGift(Long giftId, Long recipientId) {
        return giftHistoryRepository
                .findByIdAndRecipient_IdAndDeletedAtIsNull(
                        giftId,
                        recipientId
                )
                .orElseThrow(() -> new GiftException(
                        GiftErrorCode.RECEIVED_GIFT_NOT_FOUND
                ));
    }

    private String ledgerKey(Long giftId, Long senderId) {
        return LEDGER_KEY_PREFIX + giftId + ":" + senderId;
    }
}
