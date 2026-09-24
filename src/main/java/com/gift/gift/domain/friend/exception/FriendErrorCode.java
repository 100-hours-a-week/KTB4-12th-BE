package com.gift.gift.domain.friend.exception;

import com.gift.gift.global.exception.ErrorCode;

public enum FriendErrorCode {

    FRIEND_ALREADY_EXISTS(
            ErrorCode.FRIEND_ALREADY_EXISTS
    ),
    FRIEND_CANNOT_ADD_SELF(
            ErrorCode.FRIEND_CANNOT_ADD_SELF
    ),
    FRIEND_LIST_RETRIEVAL_FAILED(
            ErrorCode.INTERNAL_SERVER_ERROR,
            "친구 목록 조회에 실패했습니다. 다시 시도해 주세요."
    ),
    FRIEND_SEARCH_QUERY_REQUIRED(
            ErrorCode.INVALID_REQUEST,
            "검색어를 입력해 주세요."
    ),
    FRIEND_SEARCH_FAILED(
            ErrorCode.INTERNAL_SERVER_ERROR,
            "친구 검색에 실패했습니다. 다시 시도해 주세요."
    );

    private final ErrorCode errorCode;
    private final String message;

    FriendErrorCode(ErrorCode errorCode) {
        this(
                errorCode,
                errorCode.message()
        );
    }

    FriendErrorCode(
            ErrorCode errorCode,
            String message
    ) {
        this.errorCode = errorCode;
        this.message = message;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String message() {
        return message;
    }
}
