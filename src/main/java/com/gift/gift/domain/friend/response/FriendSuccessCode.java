package com.gift.gift.domain.friend.response;

import org.springframework.http.HttpStatus;

public enum FriendSuccessCode {

    FRIEND_LIST_RETRIEVED(
            HttpStatus.OK,
            "친구 목록을 조회했습니다."
    ),
    FRIEND_ADDED(
            HttpStatus.CREATED,
            "%s님을 친구로 추가했습니다."
    );

    private final HttpStatus status;
    private final String message;

    FriendSuccessCode(
            HttpStatus status,
            String message
    ) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }

    public String formatMessage(Object... arguments) {
        return message.formatted(arguments);
    }
}
