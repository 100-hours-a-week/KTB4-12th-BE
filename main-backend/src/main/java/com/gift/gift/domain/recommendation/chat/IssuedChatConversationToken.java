package com.gift.gift.domain.recommendation.chat;

import java.time.Instant;

public record IssuedChatConversationToken(
        String value,
        Instant issuedAt,
        Instant expiresAt
) {

    @Override
    public String toString() {
        return "IssuedChatConversationToken[REDACTED]";
    }
}
