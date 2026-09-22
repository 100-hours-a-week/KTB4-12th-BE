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
        Objects.requireNonNull(user, "사용자는 null일 수 없습니다.");
        Objects.requireNonNull(zoneId, "시간대는 null일 수 없습니다.");
        Objects.requireNonNull(
                user.getUpdatedAt(),
                "사용자 수정 시각은 null일 수 없습니다."
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
