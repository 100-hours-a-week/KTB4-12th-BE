package com.gift.gift.domain.notification.event.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        assertThat(command.referenceType()).isEqualTo(NotificationReferenceType.GIFT);
        assertThat(command.referenceId()).isEqualTo(GIFT_ID);
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
                new GiftCompletedRewardEvent(GIFT_ID, PAYER_ID, PRODUCT_NAME, 100_000L);

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
        assertThat(command.message()).isEqualTo("선물 상품 선물 완료 보상 100,000 크레딧이 지급되었습니다.");
        assertThat(command.deduplicationKey()).isEqualTo("GIFT_COMPLETED_REWARD:501:20");
    }

    @ParameterizedTest
    @CsvSource({"1,50000", "2,100000", "3,150000"})
    @DisplayName("기본·2배·3배 보상 알림에 실제 지급액과 쿠폰 적용을 표시한다")
    void handle_displaysActualRewardAndCouponMultiplier(int multiplier, long amount) {
        when(keyGenerator.generate(NotificationType.GIFT_COMPLETED_REWARD, GIFT_ID.toString(), PAYER_ID))
                .thenReturn("GIFT_COMPLETED_REWARD:501:20");
        handler.handle(new GiftCompletedRewardEvent(GIFT_ID, PAYER_ID, PRODUCT_NAME, amount, multiplier));
        NotificationCreateCommand command = captureCommand();
        String formatted = String.format(java.util.Locale.KOREA, "%,d", amount);
        assertThat(command.title()).isEqualTo(multiplier == 1
                ? "보상이 지급되었어요" : multiplier + "배 쿠폰 보상이 지급되었어요");
        assertThat(command.message()).isEqualTo(multiplier == 1
                ? PRODUCT_NAME + " 선물 완료 보상 " + formatted + " 크레딧이 지급되었습니다."
                : PRODUCT_NAME + " 선물에 " + multiplier + "배 쿠폰이 적용되어 선물 보상 총 "
                        + formatted + " 크레딧이 지급되었습니다.");
        assertThat(command.recipientId()).isEqualTo(PAYER_ID);
        assertThat(command.referenceId()).isEqualTo(GIFT_ID);
        assertThat(command.referenceType()).isEqualTo(NotificationReferenceType.GIFT);
        assertThat(command.type()).isEqualTo(NotificationType.GIFT_COMPLETED_REWARD);
        assertThat(command.deduplicationKey()).isEqualTo("GIFT_COMPLETED_REWARD:501:20");
    }

    @Test
    @DisplayName("긴 상품명은 축약하되 쿠폰 배수와 지급액은 유지한다")
    void handle_abbreviatesProductNameWithoutLosingReward() {
        when(keyGenerator.generate(NotificationType.GIFT_COMPLETED_REWARD, GIFT_ID.toString(), PAYER_ID))
                .thenReturn("GIFT_COMPLETED_REWARD:501:20");
        handler.handle(new GiftCompletedRewardEvent(GIFT_ID, PAYER_ID, "가".repeat(35), 150_000L, 3));
        assertThat(captureCommand().message()).isEqualTo("가".repeat(30)
                + "... 선물에 3배 쿠폰이 적용되어 선물 보상 총 150,000 크레딧이 지급되었습니다.");
    }

    private NotificationCreateCommand captureCommand() {
        ArgumentCaptor<NotificationCreateCommand> captor =
                ArgumentCaptor.forClass(NotificationCreateCommand.class);

        verify(notificationService).create(captor.capture());

        return captor.getValue();
    }
}
