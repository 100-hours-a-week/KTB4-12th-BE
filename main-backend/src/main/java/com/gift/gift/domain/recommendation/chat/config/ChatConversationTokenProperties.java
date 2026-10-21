package com.gift.gift.domain.recommendation.chat.config;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.ai-chat.conversation-token")
public record ChatConversationTokenProperties(
        @NotBlank String secretBase64,
        @NotBlank String issuer
) {

    public ChatConversationTokenProperties {
        if (secretBase64 == null || secretBase64.isBlank()
                || issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException(
                    "대화 토큰 설정을 확인해 주세요."
            );
        }
    }

    @Override
    public String toString() {
        return "ChatConversationTokenProperties[REDACTED]";
    }
}
