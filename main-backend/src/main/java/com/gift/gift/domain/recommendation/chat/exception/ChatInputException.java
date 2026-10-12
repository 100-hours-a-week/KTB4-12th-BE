package com.gift.gift.domain.recommendation.chat.exception;

import com.gift.gift.global.exception.ValidationErrorReason;

public class ChatInputException extends RuntimeException {
    private final ValidationErrorReason reason;

    public ChatInputException(ValidationErrorReason reason) {
        super("입력값을 확인해 주세요.");
        this.reason = reason;
    }

    public ValidationErrorReason reason() {
        return reason;
    }
}
