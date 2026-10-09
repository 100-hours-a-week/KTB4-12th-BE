package com.gift.gift.domain.bugreport.exception;

import com.gift.gift.global.exception.BusinessException;

public class BugReportException extends BusinessException {

    public BugReportException(BugReportErrorCode errorCode) {
        super(errorCode.errorCode(), errorCode.message());
    }

    public BugReportException(BugReportErrorCode errorCode, String message) {
        super(errorCode.errorCode(), message);
    }
}
