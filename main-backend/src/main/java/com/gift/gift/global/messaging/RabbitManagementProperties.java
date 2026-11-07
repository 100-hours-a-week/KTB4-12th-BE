package com.gift.gift.global.messaging;

import java.net.URI;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.rabbitmq.management")
public record RabbitManagementProperties(
        @NotNull URI url,
        @NotBlank String username,
        @NotBlank String password
) {
}
