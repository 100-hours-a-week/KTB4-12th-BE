package com.gift.gift.domain.review.response;

import org.springframework.http.HttpStatus;

public enum ReviewSuccessCode {

    REVIEW_CREATED(
            HttpStatus.CREATED,
            "리뷰를 등록했습니다."
    );

    private final HttpStatus status;
    private final String message;

    ReviewSuccessCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
