package com.gift.gift.domain.order.messaging;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.payment.mq.topology")
public record PaymentRabbitTopologyProperties(
        @DefaultValue("1")
        @Min(1)
        int initialGroupSize
) {
}
