package com.gift.gift.global.pagination;

import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

public class InvalidCursorException extends BusinessException {

    public InvalidCursorException() {
        super(ErrorCode.INVALID_CURSOR);
    }

    public InvalidCursorException(Throwable cause) {
        super(ErrorCode.INVALID_CURSOR);
        initCause(cause);
    }
}
