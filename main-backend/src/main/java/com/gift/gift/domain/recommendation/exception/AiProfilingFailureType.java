package com.gift.gift.domain.recommendation.exception;

import lombok.Getter;

@Getter
public enum AiProfilingFailureType {

    INVALID_REQUEST(false),
    INVALID_SERVICE_TOKEN(false),
    AI_SERVER_ERROR(true),
    AI_SERVICE_UNAVAILABLE(true),
    COMMUNICATION_ERROR(true),
    INVALID_RESPONSE(false);

    private final boolean retryable;

    AiProfilingFailureType(boolean retryable) {
        this.retryable = retryable;
    }

}
