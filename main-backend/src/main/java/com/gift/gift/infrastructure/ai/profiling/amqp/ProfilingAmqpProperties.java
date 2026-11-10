package com.gift.gift.infrastructure.ai.profiling.amqp;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.ai-profile.amqp")
public record ProfilingAmqpProperties(
        @DefaultValue("30")
        @Min(1)
        int confirmTimeoutSeconds,

        @DefaultValue("1")
        @Min(1)
        int initialGroupSize
) {

}
