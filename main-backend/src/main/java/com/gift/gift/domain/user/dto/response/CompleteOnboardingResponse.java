package com.gift.gift.domain.user.dto.response;

import java.util.Objects;

import com.gift.gift.domain.user.entity.User;

public record CompleteOnboardingResponse(
        boolean isFirstLogin
) {

    public static CompleteOnboardingResponse from(User user) {
        Objects.requireNonNull(user, "사용자는 null일 수 없습니다.");

        return new CompleteOnboardingResponse(
                user.isFirstLogin()
        );
    }
}
