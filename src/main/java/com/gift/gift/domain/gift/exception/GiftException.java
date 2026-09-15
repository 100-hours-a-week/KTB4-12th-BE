package com.gift.gift.domain.gift.exception;

import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

public class GiftException extends BusinessException {

    public GiftException(ErrorCode errorCode) {
        super(errorCode);
    }

    public GiftException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
