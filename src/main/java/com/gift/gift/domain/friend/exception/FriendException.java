package com.gift.gift.domain.friend.exception;

import com.gift.gift.global.exception.BusinessException;

public class FriendException extends BusinessException {

    public FriendException(FriendErrorCode errorCode) {
        super(
                errorCode.errorCode(),
                errorCode.message()
        );
    }

    public FriendException(
            FriendErrorCode errorCode,
            Throwable cause
    ) {
        super(
                errorCode.errorCode(),
                errorCode.message()
        );
        initCause(cause);
    }
}
