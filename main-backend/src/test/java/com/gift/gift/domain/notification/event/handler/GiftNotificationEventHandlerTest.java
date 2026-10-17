package com.gift.gift.domain.notification.event.handler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.gift.gift.domain.gift.event.GiftCreatedEvent;
import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.service.NotificationOutboxWriter;
import com.gift.gift.domain.notification.support.NotificationDeduplicationKeyGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GiftNotificationEventHandlerTest {

    @ParameterizedTest
    @ValueSource(ints = {29, 30, 31})
    @DisplayName("상품명이 30자를 초과하면 말줄임표를 붙인다")
    void handle_limitsProductNameInMessage(int nameLength) {
        var writer = mock(NotificationOutboxWriter.class);
        var handler = new GiftNotificationEventHandler(writer, new NotificationDeduplicationKeyGenerator());
        handler.handle(new GiftCreatedEvent(501L, 20L, "가".repeat(nameLength)));

        var captor = ArgumentCaptor.forClass(NotificationCreateCommand.class);
        verify(writer).enqueue(captor.capture());
        assertThat(captor.getValue().message())
                .isEqualTo("가".repeat(Math.min(nameLength, 30))
                        + (nameLength > 30 ? "..." : "") + " 선물이 도착했어요.");
    }

    @Test
    @DisplayName("상품명의 보조 평면 문자를 쪼개지 않고 본문을 제한한다")
    void handle_preservesSupplementaryCharacters() {
        var writer = mock(NotificationOutboxWriter.class);
        var handler = new GiftNotificationEventHandler(writer, new NotificationDeduplicationKeyGenerator());
        handler.handle(new GiftCreatedEvent(501L, 20L, "🎁".repeat(31)));

        var captor = ArgumentCaptor.forClass(NotificationCreateCommand.class);
        verify(writer).enqueue(captor.capture());
        assertThat(captor.getValue().message()).isEqualTo("🎁".repeat(30) + "... 선물이 도착했어요.");
    }

    @Test
    @DisplayName("선물 이벤트를 영속 Outbox 명령으로 변환한다")
    void handle_convertsGiftEventToNotificationCommand() {
        NotificationOutboxWriter outboxWriter = mock(NotificationOutboxWriter.class);
        NotificationDeduplicationKeyGenerator keyGenerator = mock(NotificationDeduplicationKeyGenerator.class);
        GiftNotificationEventHandler handler = new GiftNotificationEventHandler(outboxWriter, keyGenerator);
        GiftCreatedEvent event = new GiftCreatedEvent(501L, 20L, "선물 상품");
        when(keyGenerator.generate(
                com.gift.gift.domain.notification.entity.NotificationType.GIFT_RECEIVED,
                "501",
                20L
        )).thenReturn("GIFT_RECEIVED:501:20");

        handler.handle(event);

        ArgumentCaptor<NotificationCreateCommand> captor = ArgumentCaptor.forClass(NotificationCreateCommand.class);
        verify(outboxWriter).enqueue(captor.capture());
        NotificationCreateCommand command = captor.getValue();
        assertThat(command.recipientId()).isEqualTo(20L);
        assertThat(command.referenceId()).isEqualTo(501L);
        assertThat(command.deduplicationKey()).isEqualTo("GIFT_RECEIVED:501:20");
        assertThat(command.message()).contains("선물 상품");
    }
}
