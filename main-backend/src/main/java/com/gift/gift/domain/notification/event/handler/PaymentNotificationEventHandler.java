package com.gift.gift.domain.notification.event.handler;

import java.util.Locale;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.gift.gift.domain.gift.event.GiftCompletedRewardEvent;
import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;
import com.gift.gift.domain.notification.service.NotificationOutboxWriter;
import com.gift.gift.domain.notification.support.NotificationDeduplicationKeyGenerator;
import com.gift.gift.domain.notification.support.NotificationProductNameFormatter;
import com.gift.gift.domain.order.event.PaymentCanceledEvent;
import com.gift.gift.domain.order.event.PaymentCompletedEvent;

@Component
@RequiredArgsConstructor
public class PaymentNotificationEventHandler {

    private final NotificationOutboxWriter outboxWriter;
    private final NotificationDeduplicationKeyGenerator keyGenerator;

    @TransactionalEventListener(
            phase = TransactionPhase.BEFORE_COMMIT
    )
    public void handle(PaymentCompletedEvent event) {
        NotificationType type = NotificationType.PAYMENT_COMPLETED;

        String sourceIdentifier = event.orderRecordId().toString();

        outboxWriter.enqueue(
                new NotificationCreateCommand(
                        event.payerId(),
                        sourceIdentifier,
                        type,
                        NotificationReferenceType.GIFT,
                        event.giftId(),
                        "결제가 완료되었어요",
                        NotificationProductNameFormatter.abbreviate(
                                event.productName()
                        ) + " 결제가 완료되었습니다.",
                        keyGenerator.generate(
                                type,
                                sourceIdentifier,
                                event.payerId()
                        )
                )
        );
    }

    @TransactionalEventListener(
            phase = TransactionPhase.BEFORE_COMMIT
    )
    public void handle(PaymentCanceledEvent event) {
        NotificationType type = NotificationType.PAYMENT_CANCELED;

        String sourceIdentifier = event.orderRecordId().toString();

        outboxWriter.enqueue(
                new NotificationCreateCommand(
                        event.payerId(),
                        sourceIdentifier,
                        type,
                        NotificationReferenceType.PAYMENT,
                        event.orderRecordId(),
                        "결제가 취소되었어요",
                        NotificationProductNameFormatter.abbreviate(
                                event.productName()
                        ) + " 결제가 취소되었습니다.",
                        keyGenerator.generate(
                                type,
                                sourceIdentifier,
                                event.payerId()
                        )
                )
        );
    }

    @TransactionalEventListener(
            phase = TransactionPhase.BEFORE_COMMIT
    )
    public void handle(GiftCompletedRewardEvent event) {
        NotificationType type =
                NotificationType.GIFT_COMPLETED_REWARD;

        String sourceIdentifier = event.giftId().toString();

        String productName =
                NotificationProductNameFormatter.abbreviate(
                        event.productName()
                );

        String formattedAmount = String.format(
                Locale.KOREA,
                "%,d",
                event.rewardAmount()
        );

        boolean couponApplied = event.rewardMultiplier() > 1;

        String title = couponApplied
                ? event.rewardMultiplier()
                + "배 쿠폰 보상이 지급되었어요"
                : "보상이 지급되었어요";

        String message = couponApplied
                ? productName
                + " 선물에 "
                + event.rewardMultiplier()
                + "배 쿠폰이 적용되어 선물 보상 총 "
                + formattedAmount
                + " 크레딧이 지급되었습니다."
                : productName
                + " 선물 완료 보상 "
                + formattedAmount
                + " 크레딧이 지급되었습니다.";

        outboxWriter.enqueue(
                new NotificationCreateCommand(
                        event.senderId(),
                        sourceIdentifier,
                        type,
                        NotificationReferenceType.GIFT,
                        event.giftId(),
                        title,
                        message,
                        keyGenerator.generate(
                                type,
                                sourceIdentifier,
                                event.senderId()
                        )
                )
        );
    }
}
