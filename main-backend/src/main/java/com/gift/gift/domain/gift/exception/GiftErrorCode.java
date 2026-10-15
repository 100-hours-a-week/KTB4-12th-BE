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
    GIFT_NOT_ACCEPTABLE(
            ErrorCode.GIFT_NOT_ACCEPTABLE,
            "현재 상태에서는 선물을 받을 수 없습니다."
    ),

    GIFT_STATE_CONFLICT(
            ErrorCode.GIFT_STATE_CONFLICT,
            "선물 상태가 변경되었습니다. 다시 확인해 주세요."
    );

    private final ErrorCode errorCode;
    private final String message;

    GiftErrorCode(ErrorCode errorCode, String message) {
        this.errorCode = errorCode;
        this.message = message;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String message() {
        return message;
    }
}
