package com.gift.gift.global.response;

import java.util.Map;
import java.util.Objects;

public record SuccessResponse<T>(
    String message,
    T data
) {

    public SuccessResponse {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(data, "data must not be null");
    }

    public static <T> SuccessResponse<T> of(String message, T data) {
        return new SuccessResponse<>(message, data);
    }

    public static SuccessResponse<Map<String, Object>> empty(String message) {
        return new SuccessResponse<>(message, Map.of());
    }
}
