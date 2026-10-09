package com.gift.gift.domain.friend.repository;

import java.time.LocalDate;

public record FriendQueryRow(
        Long friendId,
        Long userId,
        String name,
        String email,
        LocalDate birth,
        boolean birthdayPublic
) {
}
