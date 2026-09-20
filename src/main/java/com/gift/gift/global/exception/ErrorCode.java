package com.gift.gift.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    SIGNUP_TERMS_NOT_FOUND(HttpStatus.NOT_FOUND, "현재 적용 중인 회원가입 약관이 없습니다."),
    INTERNAL_SERVER_ERROR(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "일시적인 오류가 발생했습니다. 다시 시도해 주세요."
    ),
    INVALID_EMAIL_FORMAT(HttpStatus.BAD_REQUEST, "이메일 형식을 확인해 주세요."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    REQUIRED_TERMS_NOT_AGREED(HttpStatus.BAD_REQUEST, "필수 약관에 모두 동의해 주세요."),
    INVALID_TERM_VERSION(HttpStatus.BAD_REQUEST, "약관이 변경되었습니다. 다시 확인해 주세요."),
    EMAIL_ALREADY_IN_USE(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 일치하지 않습니다."),
    TOO_MANY_REQUESTS(
        HttpStatus.TOO_MANY_REQUESTS,
        "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."
    ),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    CURRENT_PASSWORD_MISMATCH(HttpStatus.UNAUTHORIZED, "현재 비밀번호가 일치하지 않습니다."),
    PASSWORD_REUSE_NOT_ALLOWED(
        HttpStatus.CONFLICT,
        "이전 비밀번호와 다른 비밀번호를 입력해 주세요."
    ),
    PASSWORD_CHANGE_CONFLICT(
        HttpStatus.CONFLICT,
        "다른 비밀번호 변경 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요."
    ),
    INVALID_REFRESH_TOKEN(
        HttpStatus.UNAUTHORIZED,
        "인증이 만료되었거나 유효하지 않습니다. 다시 로그인해 주세요."
    ),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "페이지 정보를 확인해 주세요."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자 정보를 찾을 수 없습니다."),
    FRIEND_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 등록된 친구입니다."),
    FRIEND_CANNOT_ADD_SELF(HttpStatus.UNPROCESSABLE_CONTENT, "본인은 친구로 추가할 수 없습니다."),
    RECIPIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "수신자를 찾을 수 없습니다."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    TOO_MANY_DISLIKE_CATEGORIES(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "비선호 카테고리는 최대 5개까지 선택할 수 있습니다."
    ),
    DISLIKE_CATEGORY_NOT_AVAILABLE(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "선택할 수 없는 비선호 카테고리가 포함되어 있습니다."
    ),
    RECIPIENT_NOT_FRIEND(HttpStatus.UNPROCESSABLE_CONTENT, "친구에게만 선물할 수 있습니다."),
    INSUFFICIENT_STOCK(HttpStatus.UNPROCESSABLE_CONTENT, "상품 재고가 부족합니다."),
    GIFT_CONDITIONS_CHANGED(HttpStatus.CONFLICT, "상품 가격이 변경되었습니다. 다시 확인해 주세요."),
    IDEMPOTENCY_KEY_CONFLICT(
        HttpStatus.CONFLICT,
        "동일한 요청 키를 다른 요청에 사용할 수 없습니다."
    ),
    GIFT_NOT_FOUND(HttpStatus.NOT_FOUND, "선물 내역을 찾을 수 없습니다."),
    REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "리뷰를 찾을 수 없습니다."),
    REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 리뷰를 등록한 선물입니다."),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),

    AUTHENTICATION_TEMPORARILY_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "인증 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
    );

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return name();
    }

    public String message() {
        return message;
    }
}
