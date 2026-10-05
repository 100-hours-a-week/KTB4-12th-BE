package com.gift.gift.domain.notification.support;

import org.junit.jupiter.api.Test;

import com.gift.gift.domain.notification.entity.NotificationType;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationDeduplicationKeyGeneratorTest {

    @Test
    void generate_returnsStableKey() {
        NotificationDeduplicationKeyGenerator generator = new NotificationDeduplicationKeyGenerator();

        assertThat(generator.generate(NotificationType.GIFT_RECEIVED, "501", 20L))
                .isEqualTo("GIFT_RECEIVED:501:20");
    }
}
