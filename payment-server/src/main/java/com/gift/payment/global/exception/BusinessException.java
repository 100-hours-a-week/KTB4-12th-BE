package com.gift.payment.global.exception;

import java.util.Objects;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(
                Objects.requireNonNull(errorCode, "오류 코드는 null일 수 없습니다.").message()
        );
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
