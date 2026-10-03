package com.gift.gift.domain.review.exception;

import com.gift.gift.global.exception.ErrorCode;

public enum ReviewErrorCode {

    REVIEW_CREATE_GIFT_NOT_FOUND(
            ErrorCode.GIFT_NOT_FOUND,
            "리뷰를 작성할 선물을 찾을 수 없습니다."
    ),

    REVIEW_ALREADY_EXISTS(
            ErrorCode.REVIEW_ALREADY_EXISTS,
            "이미 리뷰를 등록한 선물입니다."
    ),

    REVIEW_CREATE_FAILED(
            ErrorCode.INTERNAL_SERVER_ERROR,
            "리뷰를 등록하지 못했습니다. 다시 시도해 주세요."
    );

    private final ErrorCode errorCode;
    private final String message;

    ReviewErrorCode(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    ReviewErrorCode(ErrorCode errorCode, String message) {
        this.errorCode = errorCode;
        this.message = message;
    }

    public ErrorCode errorCode() { return errorCode; }

    public String message() { return message; }
}
