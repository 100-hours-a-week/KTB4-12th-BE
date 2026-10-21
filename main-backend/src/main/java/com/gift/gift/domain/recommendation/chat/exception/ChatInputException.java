package com.gift.gift.domain.recommendation.chat.exception;

import com.gift.gift.global.exception.ValidationErrorReason;

public class ChatInputException extends RuntimeException {
    private final ValidationErrorReason reason;

    public ChatInputException(ValidationErrorReason reason) {
        super(ChatErrorCode.INVALID_MESSAGE.message());
        this.reason = reason;
    }

    public ValidationErrorReason reason() {
        return reason;
    }
}
