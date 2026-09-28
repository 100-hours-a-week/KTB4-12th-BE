package com.gift.gift.domain.user.dto.response;

import java.util.Objects;

import com.gift.gift.domain.user.entity.User;

public record UserSearchResponse(
        UserResult user
) {

    public static UserSearchResponse from(User user) {
        Objects.requireNonNull(
                user,
                "검색된 사용자는 null일 수 없습니다."
        );

        return new UserSearchResponse(
                new UserResult(
                        user.getId(),
                        user.getName(),
                        user.getEmail()
                )
        );
    }

    public static UserSearchResponse notFound() {
        return new UserSearchResponse(null);
    }

    public record UserResult(
            Long userId,
            String name,
            String email
    ) {
        public UserResult {
            Objects.requireNonNull(
                    userId,
                    "사용자 ID는 null일 수 없습니다."
            );
            Objects.requireNonNull(
                    name,
                    "사용자 이름은 null일 수 없습니다."
            );
            Objects.requireNonNull(
                    email,
                    "사용자 이메일은 null일 수 없습니다."
            );
        }
    }
}
