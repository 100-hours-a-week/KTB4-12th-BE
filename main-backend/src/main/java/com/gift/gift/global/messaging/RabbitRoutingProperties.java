package com.gift.gift.global.messaging;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.outbox.rabbit")
public record RabbitRoutingProperties(
        @NotBlank String exchange,
        @NotBlank String notificationRoutingKey,
        @NotBlank String searchRoutingKey
) {
}
