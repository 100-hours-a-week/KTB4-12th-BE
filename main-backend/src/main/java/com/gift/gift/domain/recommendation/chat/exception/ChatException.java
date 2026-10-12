package com.gift.gift.domain.recommendation.chat.exception;

import com.gift.gift.global.exception.BusinessException;

public class ChatException extends BusinessException {

    public ChatException(ChatErrorCode errorCode) {
        super(errorCode.errorCode(), errorCode.message());
    }
}
