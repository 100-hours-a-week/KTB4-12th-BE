package com.gift.gift.global.response;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.exception.ValidationDetail;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        String message,
        T data,
        ErrorBody error
) {
    public ApiResponse {
        Objects.requireNonNull(message, "응답 메시지는 null일 수 없습니다.");

        if ((data == null) == (error == null)) {
            throw new IllegalArgumentException(
                    "응답 데이터와 오류 정보 중 하나만 제공해야 합니다."
            );
        }
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(
                message,
                Objects.requireNonNull(
                        data,
                        "응답 데이터는 null일 수 없습니다."
                ),
                null
        );
    }

    public static ApiResponse<Map<String, Object>> success(String message) {
        return success(message, Map.of());
    }

    public static ApiResponse<Void> error(
            ErrorCode errorCode,
            String message,
            String traceId
    ) {
        return error(errorCode, message, traceId, List.of());
    }

    public static ApiResponse<Void> error(
            ErrorCode errorCode,
            String message,
            String traceId,
            List<ValidationDetail> details
    ) {
        return new ApiResponse<>(
                message,
                null,
                new ErrorBody(
                        errorCode.code(),
                        traceId,
                        List.copyOf(details)
                )
        );
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ErrorBody(
            String code,
            String traceId,
            List<ValidationDetail> details
    ) {
    }
}
