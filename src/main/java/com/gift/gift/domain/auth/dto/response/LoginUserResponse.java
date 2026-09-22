package com.gift.gift.domain.auth.dto.response;

import com.gift.gift.domain.auth.support.LoginResult;

public record LoginUserResponse(
        Long userId,
        String name,
        String email
) {

    public static LoginUserResponse from(
            LoginResult result
    ) {
        return new LoginUserResponse(
                result.userId(),
                result.name(),
                result.email()
        );
    }
}
