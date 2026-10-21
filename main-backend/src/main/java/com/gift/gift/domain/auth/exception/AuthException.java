package com.gift.gift.domain.auth.exception;

import com.gift.gift.global.exception.BusinessException;

public class AuthException extends BusinessException {

    public AuthException(AuthErrorCode errorCode) {
        super(errorCode.errorCode(), errorCode.message());
    }
}
