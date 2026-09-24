package com.gift.gift.domain.friend.dto.response;

import java.time.format.DateTimeFormatter;

import com.gift.gift.domain.friend.repository.FriendQueryRow;

public record FriendListItem(
        Long friendId,
        Long userId,
        String name,
        String email,
        String birth
) {

    private static final DateTimeFormatter BIRTH_FORMAT =
            DateTimeFormatter.ISO_LOCAL_DATE;

    public static FriendListItem from(FriendQueryRow row) {
        return new FriendListItem(
                row.friendId(),
                row.userId(),
                row.name(),
                row.email(),
                row.birthdayPublic()
                        ? BIRTH_FORMAT.format(row.birth())
                        : null
        );
    }
}
