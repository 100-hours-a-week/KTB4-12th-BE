package com.gift.gift.domain.order.exception;

import com.gift.gift.global.exception.BusinessException;

public class OrderException extends BusinessException {

    public OrderException(OrderErrorCode errorCode) {
        super(errorCode.errorCode(), errorCode.message());
    }

    public OrderException(OrderErrorCode errorCode, Throwable cause) {
        super(errorCode.errorCode(), errorCode.message());
        initCause(cause);
    }
}
