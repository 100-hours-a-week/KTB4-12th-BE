package com.gift.gift.domain.auth.dto.response;

public record EmailVerificationTokenResponse(String emailVerificationToken) {

    @Override
    public String toString() {
        return "EmailVerificationTokenResponse[REDACTED]";
    }
}
