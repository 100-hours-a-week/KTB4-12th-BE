package com.gift.gift.global.exception;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ErrorResponse(
    String message,
    ErrorBody error
) {

    public static ErrorResponse of(ErrorCode errorCode, String message, String traceId) {
        return of(errorCode, message, traceId, List.of());
    }

    public static ErrorResponse of(
        ErrorCode errorCode,
        String message,
        String traceId,
        List<ValidationDetail> details
    ) {
        return new ErrorResponse(
            message,
            new ErrorBody(errorCode.code(), traceId, List.copyOf(details))
        );
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ErrorBody(
        String code,
        String traceId,
        List<ValidationDetail> details
    ) {
    }

    public record ValidationDetail(
        String field,
        ValidationErrorReason reason
    ) {
    }
}
