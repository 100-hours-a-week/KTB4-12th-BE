package com.gift.gift.domain.randombox.exception;

import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

public class RandomBoxNotFoundException extends BusinessException {
    public RandomBoxNotFoundException() {
        super(ErrorCode.RANDOM_BOX_NOT_FOUND);
    }
}
