package com.gift.gift.domain.notification.event.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.gift.gift.domain.gift.event.GiftCompletedRewardEvent;
import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;
import com.gift.gift.domain.notification.service.NotificationService;
import com.gift.gift.domain.notification.support.NotificationDeduplicationKeyGenerator;
import com.gift.gift.domain.order.event.PaymentCanceledEvent;
import com.gift.gift.domain.order.event.PaymentCompletedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentNotificationEventHandlerTest {

    private static final Long ORDER_RECORD_ID = 101L;
    private static final Long PAYER_ID = 20L;
    private static final Long GIFT_ID = 501L;
    private static final String PRODUCT_NAME = "선물 상품";

    private NotificationService notificationService;
    private NotificationDeduplicationKeyGenerator keyGenerator;
    private PaymentNotificationEventHandler handler;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        keyGenerator = mock(NotificationDeduplicationKeyGenerator.class);
        handler = new PaymentNotificationEventHandler(notificationService, keyGenerator);
    }

    @Test
    @DisplayName("결제 완료 이벤트를 결제 완료 알림 명령으로 변환한다")
    void handle_convertsPaymentCompletedEvent() {
        PaymentCompletedEvent event =
                new PaymentCompletedEvent(
                        ORDER_RECORD_ID,
                        PAYER_ID,
                        GIFT_ID,
                        PRODUCT_NAME
                );

        when(keyGenerator.generate(
                NotificationType.PAYMENT_COMPLETED,
                ORDER_RECORD_ID.toString(),
                PAYER_ID
        )).thenReturn("PAYMENT_COMPLETED:101:20");

        handler.handle(event);

        NotificationCreateCommand command = captureCommand();

        assertThat(command.recipientId()).isEqualTo(PAYER_ID);
        assertThat(command.sourceIdentifier()).isEqualTo("101");
        assertThat(command.type()).isEqualTo(NotificationType.PAYMENT_COMPLETED);
        assertThat(command.referenceType()).isEqualTo(NotificationReferenceType.PAYMENT);
        assertThat(command.referenceId()).isEqualTo(ORDER_RECORD_ID);
        assertThat(command.title()).isEqualTo("결제가 완료되었어요");
        assertThat(command.message()).isEqualTo("선물 상품 결제가 완료되었습니다.");
        assertThat(command.deduplicationKey()).isEqualTo("PAYMENT_COMPLETED:101:20");
    }

    @Test
    @DisplayName("결제 취소 이벤트를 결제 취소 알림 명령으로 변환한다")
    void handle_convertsPaymentCanceledEvent() {
        PaymentCanceledEvent event =
                new PaymentCanceledEvent(ORDER_RECORD_ID, PAYER_ID, GIFT_ID, PRODUCT_NAME);

        when(keyGenerator.generate(
                NotificationType.PAYMENT_CANCELED,
                ORDER_RECORD_ID.toString(),
                PAYER_ID
        )).thenReturn("PAYMENT_CANCELED:101:20");

        handler.handle(event);

        NotificationCreateCommand command = captureCommand();

        assertThat(command.recipientId()).isEqualTo(PAYER_ID);
        assertThat(command.sourceIdentifier()).isEqualTo("101");
        assertThat(command.type()).isEqualTo(NotificationType.PAYMENT_CANCELED);
        assertThat(command.referenceType()).isEqualTo(NotificationReferenceType.PAYMENT);
        assertThat(command.referenceId()).isEqualTo(ORDER_RECORD_ID);
        assertThat(command.title()).isEqualTo("결제가 취소되었어요");
        assertThat(command.message()).isEqualTo("선물 상품 결제가 취소되었습니다.");
        assertThat(command.deduplicationKey()).isEqualTo("PAYMENT_CANCELED:101:20");
    }

    @Test
    @DisplayName("선물 완료 보상 이벤트를 발신자 보상 알림 명령으로 변환한다")
    void handle_convertsGiftCompletedRewardEvent() {
        GiftCompletedRewardEvent event =
                new GiftCompletedRewardEvent(GIFT_ID, PAYER_ID, PRODUCT_NAME);

        when(keyGenerator.generate(
                NotificationType.GIFT_COMPLETED_REWARD,
                GIFT_ID.toString(),
                PAYER_ID
        )).thenReturn("GIFT_COMPLETED_REWARD:501:20");

        handler.handle(event);

        NotificationCreateCommand command = captureCommand();

        assertThat(command.recipientId()).isEqualTo(PAYER_ID);
        assertThat(command.sourceIdentifier()).isEqualTo("501");
        assertThat(command.type()).isEqualTo(NotificationType.GIFT_COMPLETED_REWARD);
        assertThat(command.referenceType()).isEqualTo(NotificationReferenceType.GIFT);
        assertThat(command.referenceId()).isEqualTo(GIFT_ID);
        assertThat(command.title()).isEqualTo("보상이 지급되었어요");
        assertThat(command.message()).isEqualTo("선물 상품 선물 완료 보상이 지급되었습니다.");
        assertThat(command.deduplicationKey()).isEqualTo("GIFT_COMPLETED_REWARD:501:20");
    }

    private NotificationCreateCommand captureCommand() {
        ArgumentCaptor<NotificationCreateCommand> captor =
                ArgumentCaptor.forClass(NotificationCreateCommand.class);

        verify(notificationService).create(captor.capture());

        return captor.getValue();
    }
}
