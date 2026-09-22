package com.gift.gift.global.security;

import java.time.Clock;
import java.time.Instant;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public class AccessTokenClaimsValidator
        implements OAuth2TokenValidator<Jwt> {

    private final Clock clock;

    public AccessTokenClaimsValidator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            String subject = jwt.getSubject();

            if (subject == null || !subject.matches("[1-9][0-9]*")) {
                return failure();
            }

            Long.parseLong(subject);

            Instant issuedAt = jwt.getIssuedAt();
            Instant expiresAt = jwt.getExpiresAt();
            Instant now = clock.instant();

            if (issuedAt == null || expiresAt == null) {
                return failure();
            }

            if (issuedAt.isAfter(now)
                    || !expiresAt.isAfter(now)
                    || !expiresAt.isAfter(issuedAt)) {
                return failure();
            }

            return OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException exception) {
            return failure();
        }
    }

    private OAuth2TokenValidatorResult failure() {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(
                        "invalid_token",
                        "Invalid access token claims",
                        null
                )
        );
    }
}
