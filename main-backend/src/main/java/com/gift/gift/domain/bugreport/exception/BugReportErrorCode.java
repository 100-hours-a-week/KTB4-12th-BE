package com.gift.gift.domain.bugreport.exception;

import com.gift.gift.global.exception.ErrorCode;

public enum BugReportErrorCode {

    PAYLOAD_REQUIRED(
            ErrorCode.INVALID_REQUEST,
            "payload_json은 필수입니다."
    ),
    PAYLOAD_TOO_LARGE(
            ErrorCode.INVALID_REQUEST,
            "JSON 요청은 UTF-8 기준 10KB 이하여야 합니다."
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
    GOOGLE_SHEETS_NOT_CONFIGURED(
            ErrorCode.BUG_REPORT_INTEGRATION_NOT_CONFIGURED
    ),
    DELIVERY_FAILED(ErrorCode.BUG_REPORT_DELIVERY_FAILED),
    ACCEPTANCE_FAILED(ErrorCode.SERVICE_UNAVAILABLE, "버그 제보를 접수하지 못했습니다. 같은 reportId로 재시도해 주세요.");

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
