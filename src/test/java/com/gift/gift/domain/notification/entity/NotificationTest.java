package com.gift.gift.domain.notification.entity;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class NotificationTest {

    private static final LocalDateTime READ_AT = LocalDateTime.of(2026, 10, 4, 12, 0);

    @Test
    @DisplayName("연결 대상이 없는 알림을 생성할 수 있다")
    void constructor_allowsNotificationWithoutReference() {
        Notification notification = new Notification(
                20L,
                NotificationType.GIFT_COMPLETED_REWARD,
                null,
                null,
                "보상이 지급되었어요",
                "선물 완료 보상이 지급되었습니다.",
                "GIFT_COMPLETED_REWARD:501:20"
        );

        assertThat(notification.getReferenceType()).isNull();
        assertThat(notification.getReferenceId()).isNull();
    }

    @Test
    @DisplayName("연결 대상 종류와 식별자는 함께 존재하거나 함께 없어야 한다")
    void constructor_rejectsIncompleteReference() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Notification(
                        20L,
                        NotificationType.DELIVERY_SHIPPING,
                        NotificationReferenceType.DELIVERY,
                        null,
                        "배송 중이에요",
                        "선물이 배송 중입니다.",
                        "DELIVERY_SHIPPING:501:20"
                ));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Notification(
                        20L,
                        NotificationType.DELIVERY_SHIPPING,
                        null,
                        501L,
                        "배송 중이에요",
                        "선물이 배송 중입니다.",
                        "DELIVERY_SHIPPING:501:20"
                ));
    }

    @Test
    @DisplayName("알림은 최초 읽음 시각을 유지한다")
    void markAsRead_preservesFirstReadTime() {
        Notification notification = new Notification(
                20L,
                NotificationType.PAYMENT_COMPLETED,
                NotificationReferenceType.PAYMENT,
                501L,
                "결제가 완료되었어요",
                "선물 결제가 완료되었습니다.",
                "PAYMENT_COMPLETED:501:20"
        );

        notification.markAsRead(READ_AT);
        notification.markAsRead(READ_AT.plusHours(1));

        assertThat(notification.isRead()).isTrue();
        assertThat(notification.getReadAt()).isEqualTo(READ_AT);
    }
}
