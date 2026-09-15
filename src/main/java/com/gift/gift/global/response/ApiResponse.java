package com.gift.gift.global.response;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.exception.ValidationErrorReason;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
    String message,
    T data,
    ErrorBody error
) {

    public ApiResponse {
        Objects.requireNonNull(message, "message must not be null");

        if ((data == null) == (error == null)) {
            throw new IllegalArgumentException("Either data or error must be provided");
        }
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(message, Objects.requireNonNull(data, "data must not be null"), null);
    }

    public static ApiResponse<Map<String, Object>> success(String message) {
        return success(message, Map.of());
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, String message, String traceId) {
        return error(errorCode, message, traceId, List.of());
    }

    public static ApiResponse<Void> error(
        ErrorCode errorCode,
        String message,
        String traceId,
        List<ValidationDetail> details
    ) {
        ErrorBody error = new ErrorBody(
            errorCode.code(),
            traceId,
            List.copyOf(details)
        );

        return new ApiResponse<>(message, null, error);
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
