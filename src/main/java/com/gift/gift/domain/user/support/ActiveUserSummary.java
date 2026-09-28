package com.gift.gift.domain.user.support;

import java.util.Objects;

import com.gift.gift.domain.user.entity.User;

public record ActiveUserSummary(
        Long userId,
        String name
) {

    public ActiveUserSummary {
        Objects.requireNonNull(
                userId,
                "사용자 식별자는 null일 수 없습니다."
        );
        Objects.requireNonNull(
                name,
                "사용자 이름은 null일 수 없습니다."
        );
    }

    public static ActiveUserSummary from(User user) {
        Objects.requireNonNull(
                user,
                "사용자는 null일 수 없습니다."
        );

        return new ActiveUserSummary(
                user.getId(),
                user.getName()
        );
    }
}
