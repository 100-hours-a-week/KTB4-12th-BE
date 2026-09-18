package com.gift.gift.domain.user.dto.response;

import com.gift.gift.domain.user.entity.User;

public record SignupResponse(
        Long userId
) {
    public static SignupResponse from(User user) {
        return new SignupResponse(user.getId());
    }
}
