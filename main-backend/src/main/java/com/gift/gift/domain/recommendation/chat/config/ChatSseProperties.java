package com.gift.gift.domain.recommendation.chat.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.ai-chat.sse")
public record ChatSseProperties(
        Duration idleTimeout,
        Duration totalTimeout,
        int maxConnections
) {

    public ChatSseProperties {
        if (!valid(idleTimeout)
                || !valid(totalTimeout)
                || totalTimeout.compareTo(Duration.ofSeconds(180)) <= 0
                || totalTimeout.compareTo(idleTimeout) <= 0
                || maxConnections < 1) {
            throw new IllegalArgumentException(
                    "채팅 SSE 설정을 확인해 주세요."
            );
        }
    }

    private static boolean valid(Duration duration) {
        return duration != null
                && duration.toMillis() > 0
                && duration.toMillis() <= Integer.MAX_VALUE;
    }
}
