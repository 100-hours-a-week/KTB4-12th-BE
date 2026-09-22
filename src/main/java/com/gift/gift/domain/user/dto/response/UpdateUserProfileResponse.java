package com.gift.gift.domain.user.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;

import com.gift.gift.domain.user.entity.User;

public record UpdateUserProfileResponse(
        LocalDate birth,
        boolean isBirthdayPublic,
        OffsetDateTime updatedAt
) {

    public static UpdateUserProfileResponse from(
            User user,
            ZoneId zoneId
    ) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(zoneId, "zoneId must not be null");
        Objects.requireNonNull(
                user.getUpdatedAt(),
                "user.updatedAt must not be null"
        );

        return new UpdateUserProfileResponse(
                user.getBirth(),
                user.isBirthdayPublic(),
                user.getUpdatedAt()
                        .atZone(zoneId)
                        .toOffsetDateTime()
        );
    }
}
