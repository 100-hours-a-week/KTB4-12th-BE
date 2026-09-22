package com.gift.gift.domain.friend.exception;

import com.gift.gift.global.exception.ErrorCode;

public enum FriendErrorCode {

    FRIEND_ALREADY_EXISTS(
            ErrorCode.FRIEND_ALREADY_EXISTS
    ),
    FRIEND_CANNOT_ADD_SELF(
            ErrorCode.FRIEND_CANNOT_ADD_SELF
    );

    private final ErrorCode errorCode;
    private final String message;

    FriendErrorCode(ErrorCode errorCode) {
        this.errorCode = errorCode;
        this.message = errorCode.message();
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String message() {
        return message;
    }
}
