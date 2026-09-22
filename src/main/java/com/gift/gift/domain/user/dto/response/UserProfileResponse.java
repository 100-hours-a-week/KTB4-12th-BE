package com.gift.gift.domain.user.dto.response;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;

import com.gift.gift.domain.gift.repository.GiftCountRow;
import com.gift.gift.domain.user.entity.User;

public record UserProfileResponse(
        Long userId,
        String name,
        String email,
        LocalDate birth,
        boolean isBirthdayPublic,
        GiftSummary giftSummary
) {

    public static UserProfileResponse from(
            User user,
            GiftCountRow giftCount,
            LocalDate today,
            ZoneId zoneId
    ) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(
                giftCount,
                "giftCount must not be null"
        );
        Objects.requireNonNull(today, "today must not be null");
        Objects.requireNonNull(zoneId, "zoneId must not be null");

        OffsetDateTime from = today.withDayOfYear(1)
                .atStartOfDay(zoneId)
                .toOffsetDateTime();

        OffsetDateTime to = today
                .atTime(LocalTime.of(23, 59, 59))
                .atZone(zoneId)
                .toOffsetDateTime();

        return new UserProfileResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getBirth(),
                user.isBirthdayPublic(),
                new GiftSummary(
                        giftCount.sentCount(),
                        giftCount.receivedCount(),
                        new Period(from, to)
                )
        );
    }

    public record GiftSummary(
            long sentCount,
            long receivedCount,
            Period period
    ) {
    }

    public record Period(
            OffsetDateTime from,
            OffsetDateTime to
    ) {
    }
}
