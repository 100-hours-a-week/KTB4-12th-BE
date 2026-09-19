package com.gift.gift.global.security;

import java.time.Instant;

public record IssuedAccessToken(
        String value,
        Instant issuedAt,
        Instant expiresAt
) {
    @Override
    public String toString() {
        return "IssuedAccessToken[REDACTED]";
    }
}
