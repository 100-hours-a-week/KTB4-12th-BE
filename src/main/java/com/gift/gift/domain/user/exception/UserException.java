package com.gift.gift.domain.user.exception;

import com.gift.gift.global.exception.BusinessException;

public class UserException extends BusinessException {

    public UserException(UserErrorCode errorCode) {
        super(errorCode.errorCode(), errorCode.message());
    }
}
