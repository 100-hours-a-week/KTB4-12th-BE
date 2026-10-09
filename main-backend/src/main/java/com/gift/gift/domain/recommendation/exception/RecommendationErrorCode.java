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
    ),

    RECIPIENT_ID_MISMATCH(
            ErrorCode.RECIPIENT_ID_MISMATCH,
            "요청 경로와 본문의 수신자 ID가 일치하지 않습니다."
    ),

    INVALID_CALLBACK_RECIPIENT(
            ErrorCode.INVALID_REQUEST,
            "콜백을 처리할 수신자 프로파일이 없습니다."
    ),

    INVALID_RECOMMENDED_PRODUCT(
            ErrorCode.INVALID_REQUEST,
            "존재하지 않거나 삭제된 추천 상품이 포함되어 있습니다."
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
