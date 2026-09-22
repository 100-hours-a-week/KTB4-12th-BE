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
                "userId must not be null"
        );
        Objects.requireNonNull(
                name,
                "name must not be null"
        );
    }

    public static ActiveUserSummary from(User user) {
        Objects.requireNonNull(
                user,
                "user must not be null"
        );

        return new ActiveUserSummary(
                user.getId(),
                user.getName()
        );
    }
}
