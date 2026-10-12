package com.gift.payment.global.response;

import java.util.Objects;

public record ApiSuccessResponse<T>(String message, T data) {

    public ApiSuccessResponse {

        Objects.requireNonNull(message, "응답 메시지는 null일 수 없습니다.");
        Objects.requireNonNull(data, "응답 데이터는 null일 수 없습니다.");
    }

    public static <T> ApiSuccessResponse<T> of(String message, T data) {
        return new ApiSuccessResponse<>(message, data);
    }
}
