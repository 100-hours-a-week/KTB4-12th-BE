package com.gift.gift.domain.product.exception;

import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

public class ProductException extends BusinessException {

    public ProductException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ProductException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
