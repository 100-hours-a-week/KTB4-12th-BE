package com.gift.gift.global.pagination;

import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

public class InvalidCursorException extends BusinessException {

    private static final String MESSAGE = "커서를 확인해 주세요.";

    public InvalidCursorException() {
        super(ErrorCode.INVALID_CURSOR, MESSAGE);
    }

    public InvalidCursorException(Throwable cause) {
        super(ErrorCode.INVALID_CURSOR, MESSAGE);
        initCause(cause);
    }
}
