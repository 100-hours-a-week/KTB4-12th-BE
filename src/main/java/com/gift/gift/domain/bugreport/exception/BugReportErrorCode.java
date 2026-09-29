package com.gift.gift.domain.bugreport.exception;

import com.gift.gift.global.exception.ErrorCode;

public enum BugReportErrorCode {

    PAYLOAD_REQUIRED(
            ErrorCode.INVALID_REQUEST,
            "payload_json은 필수입니다."
    ),
    PAYLOAD_TOO_LARGE(
            ErrorCode.INVALID_REQUEST,
            "payload_json 크기를 확인해 주세요."
    ),
    PAYLOAD_INVALID_FORMAT(
            ErrorCode.INVALID_REQUEST,
            "payload_json 형식을 확인해 주세요."
    ),
    DESCRIPTION_REQUIRED(
            ErrorCode.INVALID_REQUEST,
            "버그 설명을 입력해 주세요."
    ),
    SCREENSHOT_INVALID_TYPE(
            ErrorCode.INVALID_REQUEST,
            "스크린샷은 PNG 또는 JPEG 형식만 허용됩니다."
    ),
    SCREENSHOT_TOO_LARGE(
            ErrorCode.INVALID_REQUEST,
            "스크린샷 파일 크기를 확인해 주세요."
    ),
    ATTACHMENT_READ_FAILED(
            ErrorCode.INVALID_REQUEST,
            "첨부 파일을 읽을 수 없습니다."
    ),
    RATE_LIMIT_EXCEEDED(ErrorCode.TOO_MANY_REQUESTS),
    WEBHOOK_NOT_CONFIGURED(
            ErrorCode.BUG_REPORT_WEBHOOK_NOT_CONFIGURED
    ),
    DELIVERY_FAILED(ErrorCode.BUG_REPORT_DELIVERY_FAILED);

    private final ErrorCode errorCode;
    private final String message;

    BugReportErrorCode(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    BugReportErrorCode(ErrorCode errorCode, String message) {
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
