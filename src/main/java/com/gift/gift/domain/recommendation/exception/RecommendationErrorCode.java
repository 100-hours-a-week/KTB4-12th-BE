package com.gift.gift.domain.recommendation.exception;

import com.gift.gift.global.exception.ErrorCode;

public enum RecommendationErrorCode {

    INVALID_CALLBACK_VERSION(
            ErrorCode.INVALID_REQUEST,
            "콜백 버전이 현재 요청 버전의 범위를 벗어났습니다."
    ),

    SOURCE_VERSION_NOT_DISPATCHED(
            ErrorCode.INVALID_REQUEST,
            "발송된 프로파일링 요청 버전이 아닙니다."
    ),

    STALE_SOURCE_VERSION(
            ErrorCode.STALE_SOURCE_VERSION,
            "이미 저장된 추천 결과보다 오래된 버전입니다."
    );

    private final ErrorCode errorCode;
    private final String message;

    RecommendationErrorCode(
            ErrorCode errorCode,
            String message
    ) {
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
