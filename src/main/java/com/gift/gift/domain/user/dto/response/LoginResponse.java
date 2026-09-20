package com.gift.gift.domain.user.dto.response;

import com.gift.gift.domain.user.support.LoginResult;

public record LoginResponse(
        String accessToken,
        long expiresIn,
        LoginUserResponse user,
        boolean isFirstLogin
) {

    public static LoginResponse from(
            LoginResult result
    ) {
        return new LoginResponse(
                result.accessToken(),
                result.expiresIn(),
                LoginUserResponse.from(result),
                result.isFirstLogin()
        );
    }
}
