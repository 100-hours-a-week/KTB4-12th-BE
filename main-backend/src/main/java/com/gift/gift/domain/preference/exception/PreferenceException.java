package com.gift.gift.domain.preference.exception;

import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

public class PreferenceException extends BusinessException {

    public PreferenceException(ErrorCode errorCode) {
        super(errorCode);
    }
}
