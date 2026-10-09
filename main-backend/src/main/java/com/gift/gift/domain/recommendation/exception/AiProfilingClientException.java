package com.gift.gift.domain.recommendation.exception;

import java.util.Objects;

import lombok.Getter;

@Getter
public class AiProfilingClientException extends RuntimeException {

    private final AiProfilingFailureType failureType;

    public AiProfilingClientException(
            AiProfilingFailureType failureType,
            String message
    ) {
        super(message);
        this.failureType = Objects.requireNonNull(failureType);
    }

    public AiProfilingClientException(
            AiProfilingFailureType failureType,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.failureType = Objects.requireNonNull(failureType);
    }

    public boolean isRetryable() {
        return failureType.isRetryable();
    }
}
