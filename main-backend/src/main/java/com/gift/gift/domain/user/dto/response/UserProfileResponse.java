package com.gift.gift.domain.user.dto.response;

import java.time.LocalDate;
import java.util.Objects;

import com.gift.gift.domain.gift.repository.GiftCountRow;
import com.gift.gift.domain.user.entity.User;

public record UserProfileResponse(
        Long userId,
        String name,
        String email,
        LocalDate birth,
        boolean isBirthdayPublic,
        long creditBalance,
        GiftSummary giftSummary
) {

    public static UserProfileResponse from(User user, long creditBalance, GiftCountRow giftCount) {

        Objects.requireNonNull(user, "사용자는 null일 수 없습니다.");
        Objects.requireNonNull(giftCount, "선물 건수는 null일 수 없습니다.");

        return new UserProfileResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getBirth(),
                user.isBirthdayPublic(),
                creditBalance,
                new GiftSummary(
                        giftCount.sentCount(),
                        giftCount.receivedCount()
                )
        );
    }

    public record GiftSummary(long sentCount, long receivedCount) {
    }
}
