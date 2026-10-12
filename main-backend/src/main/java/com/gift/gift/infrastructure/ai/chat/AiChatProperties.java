package com.gift.gift.infrastructure.ai.chat;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai-chat.client")
public record AiChatProperties(
        URI baseUrl,
        String serviceToken,
        Duration connectTimeout,
        Duration readTimeout
) {
    public AiChatProperties {
        if (baseUrl == null
                || baseUrl.getHost() == null
                || !("https".equals(baseUrl.getScheme())
                || "http".equals(baseUrl.getScheme()))
                || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null
                || serviceToken == null
                || serviceToken.isBlank()
                || serviceToken.contains("\r")
                || serviceToken.contains("\n")
                || !valid(connectTimeout)
                || !valid(readTimeout)) {
            throw new IllegalArgumentException(
                    "AI 채팅 클라이언트 설정을 확인해 주세요."
            );
        }
    }

    private static boolean valid(Duration duration) {
        return duration != null
                && !duration.isNegative()
                && !duration.isZero()
                && duration.toMillis() >= 1
                && duration.toMillis() <= Integer.MAX_VALUE;
    }

    @Override
    public String toString() {
        return "AiChatProperties[REDACTED]";
    }
}
