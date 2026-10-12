package com.gift.gift.domain.recommendation.chat.exception;

import com.gift.gift.global.exception.ErrorCode;

public enum ChatErrorCode {

    INVALID_REQUEST(
            ErrorCode.INVALID_REQUEST,
            "요청 형식이나 값이 올바르지 않습니다."
    ),
    INVALID_MESSAGE(
            ErrorCode.INVALID_REQUEST,
            "입력값을 확인해 주세요."
    ),
    RECIPIENT_NOT_FOUND(
            ErrorCode.RECIPIENT_NOT_FOUND,
            "선택한 수신자 정보를 확인할 수 없습니다."
    ),
    RESOURCE_NOT_FOUND(
            ErrorCode.NOT_FOUND,
            "대화 또는 질문을 찾을 수 없습니다."
    ),
    CONVERSATION_CONFLICT(
            ErrorCode.CONFLICT,
            "같은 대화 ID에 다른 사용자 정보를 사용할 수 없습니다."
    ),
    TURN_CONFLICT(
            ErrorCode.CONFLICT,
            "질문 ID 또는 현재 대화 처리 상태가 요청과 충돌합니다."
    ),
    PAYLOAD_TOO_LARGE(
            ErrorCode.PAYLOAD_TOO_LARGE,
            "요청 본문 크기가 너무 큽니다."
    ),
    RATE_LIMIT_EXCEEDED(
            ErrorCode.TOO_MANY_REQUESTS,
            "요청이 너무 많아요. 잠시 후 다시 시도해주세요."
    ),
    AI_SERVICE_UNAVAILABLE(
            ErrorCode.SERVICE_UNAVAILABLE,
            "AI 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요."
    );

    private final ErrorCode errorCode;
    private final String message;

    ChatErrorCode(ErrorCode errorCode, String message) {
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
