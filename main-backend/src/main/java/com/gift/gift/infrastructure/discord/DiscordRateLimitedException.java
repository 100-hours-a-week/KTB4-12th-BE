package com.gift.gift.infrastructure.discord;

class DiscordRateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    DiscordRateLimitedException(long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
