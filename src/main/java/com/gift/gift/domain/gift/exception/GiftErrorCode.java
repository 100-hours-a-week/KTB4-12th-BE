package com.gift.gift.domain.gift.exception;

import com.gift.gift.global.exception.ErrorCode;

public enum GiftErrorCode {

    SENT_GIFT_NOT_FOUND(
            ErrorCode.GIFT_NOT_FOUND,
            "보낸 선물 내역을 찾을 수 없습니다."
    ),

    RECEIVED_GIFT_NOT_FOUND(
            ErrorCode.GIFT_NOT_FOUND,
            "받은 선물 내역을 찾을 수 없습니다."
    ),

    GIFT_CANNOT_SEND_TO_SELF(
            ErrorCode.GIFT_CANNOT_SEND_TO_SELF
    );

    private final ErrorCode errorCode;
    private final String message;

    GiftErrorCode(ErrorCode errorCode, String message) {
        this.errorCode = errorCode;
        this.message = message;
    }

    GiftErrorCode(ErrorCode errorCode) {
        this.errorCode = errorCode;
        this.message = errorCode.message();
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String message() {
        return message;
    }
}
