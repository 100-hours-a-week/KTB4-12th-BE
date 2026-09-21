package com.gift.gift.domain.user.dto.response;

import com.gift.gift.domain.user.support.TokenRefreshResult;

public record TokenRefreshResponse(
        String accessToken,
        long expiresIn
) {

    public static TokenRefreshResponse from(
            TokenRefreshResult result
    ) {
        return new TokenRefreshResponse(
                result.accessToken(),
                result.expiresIn()
        );
    }
}
