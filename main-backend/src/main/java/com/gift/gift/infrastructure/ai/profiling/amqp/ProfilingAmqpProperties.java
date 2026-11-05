package com.gift.gift.infrastructure.ai.profiling.amqp;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.ai-profile.amqp")
public record ProfilingAmqpProperties(
        @DefaultValue("127.0.0.1")
        @NotBlank
        String host,

        @DefaultValue("5672")
        @Min(1)
        @Max(65535)
        int port,

        @NotBlank
        String username,

        @NotBlank
        String password,

        @DefaultValue("profiling")
        @NotBlank
        String virtualHost,

        @DefaultValue("3000")
        @Min(1)
        int connectionTimeoutMs,

        @DefaultValue("20")
        @Min(1)
        int heartbeatSeconds,

        @DefaultValue("30")
        @Min(1)
        int confirmTimeoutSeconds,

        @DefaultValue("1")
        @Min(1)
        int initialGroupSize
) {

    @Override
    public String toString() {
        return "ProfilingAmqpProperties[credentials=REDACTED]";
    }
}
