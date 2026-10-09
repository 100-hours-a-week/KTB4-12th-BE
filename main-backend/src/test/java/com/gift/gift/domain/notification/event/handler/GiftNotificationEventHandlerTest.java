package com.gift.gift.domain.notification.event.handler;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.gift.gift.domain.gift.event.GiftCreatedEvent;
import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.service.NotificationService;
import com.gift.gift.domain.notification.support.NotificationDeduplicationKeyGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GiftNotificationEventHandlerTest {

    @Test
    void handle_convertsGiftEventToNotificationCommand() {
        NotificationService notificationService = mock(NotificationService.class);
        NotificationDeduplicationKeyGenerator keyGenerator = mock(NotificationDeduplicationKeyGenerator.class);
        GiftNotificationEventHandler handler = new GiftNotificationEventHandler(notificationService, keyGenerator);
        GiftCreatedEvent event = new GiftCreatedEvent(501L, 20L, "선물 상품");
        when(keyGenerator.generate(
                com.gift.gift.domain.notification.entity.NotificationType.GIFT_RECEIVED,
                "501",
                20L
        )).thenReturn("GIFT_RECEIVED:501:20");

        handler.handle(event);

        ArgumentCaptor<NotificationCreateCommand> captor = ArgumentCaptor.forClass(NotificationCreateCommand.class);
        verify(notificationService).create(captor.capture());
        NotificationCreateCommand command = captor.getValue();
        assertThat(command.recipientId()).isEqualTo(20L);
        assertThat(command.referenceId()).isEqualTo(501L);
        assertThat(command.deduplicationKey()).isEqualTo("GIFT_RECEIVED:501:20");
        assertThat(command.message()).contains("선물 상품");
    }
}
