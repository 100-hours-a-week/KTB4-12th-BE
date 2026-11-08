package com.gift.gift.domain.bugreport.exception;

import com.gift.gift.global.exception.ErrorCode;

public enum BugReportErrorCode {

    PAYLOAD_TOO_LARGE(
            ErrorCode.INVALID_REQUEST,
            "JSON 요청은 UTF-8 기준 10KB 이하여야 합니다."
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
