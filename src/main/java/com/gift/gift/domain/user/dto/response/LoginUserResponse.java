package com.gift.gift.domain.user.dto.response;

import com.gift.gift.domain.user.support.LoginResult;

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
