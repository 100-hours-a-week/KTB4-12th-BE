package com.gift.gift.domain.auth.response;

import org.springframework.http.HttpStatus;

public enum AuthSuccessCode {

    LOGIN_COMPLETED(HttpStatus.OK, "로그인에 성공했습니다."),
    TOKEN_REFRESHED(HttpStatus.OK, "토큰을 재발급했습니다."),
    LOGOUT_COMPLETED(HttpStatus.OK, "로그아웃이 완료되었습니다.");

    private final HttpStatus status;
    private final String message;

    AuthSuccessCode(HttpStatus status, String message) {
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
