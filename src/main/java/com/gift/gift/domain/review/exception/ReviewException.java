package com.gift.gift.domain.review.exception;

import com.gift.gift.global.exception.BusinessException;

public class ReviewException extends BusinessException {

    public ReviewException(ReviewErrorCode errorCode) {
        super(errorCode.errorCode(), errorCode.message());
    }

    public ReviewException(ReviewErrorCode errorCode, Throwable cause) {
        super(errorCode.errorCode(), errorCode.message());
        initCause(cause);
    }
}
