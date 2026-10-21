package com.gift.payment.global.response;

import java.util.Objects;

import com.gift.payment.global.exception.ErrorCode;

public record ApiErrorResponse(
        String message,
        ErrorBody error
) {

    public ApiErrorResponse {
        Objects.requireNonNull(message, "오류 응답 메시지는 null일 수 없습니다.");
        Objects.requireNonNull(error, "오류 응답 본문은 null일 수 없습니다.");
    }

    public static ApiErrorResponse from(ErrorCode errorCode, String traceId) {

        return new ApiErrorResponse(
                errorCode.message(),
                new ErrorBody(
                        errorCode.code(),
                        traceId
                )
        );
    }

    public record ErrorBody(String code, String traceId) {

        public ErrorBody {
            Objects.requireNonNull(code, "오류 코드는 null일 수 없습니다.");
            Objects.requireNonNull(traceId, "추적 ID는 null일 수 없습니다.");
        }
    }
}
