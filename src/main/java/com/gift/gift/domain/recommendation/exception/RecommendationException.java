package com.gift.gift.domain.recommendation.exception;

import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

public class RecommendationException extends BusinessException {

    public RecommendationException(RecommendationErrorCode errorCode) {
        super(errorCode.errorCode(), errorCode.message());
    }

    public RecommendationException(ErrorCode errorCode) {
        super(errorCode);
    }

    public RecommendationException(
            ErrorCode errorCode,
            String message
    ) {
        super(errorCode, message);
    }
}
