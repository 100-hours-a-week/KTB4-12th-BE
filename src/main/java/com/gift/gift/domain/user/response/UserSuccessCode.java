package com.gift.gift.domain.user.response;

import org.springframework.http.HttpStatus;

public enum UserSuccessCode {

    SIGNUP_TERMS_RETRIEVED(
            HttpStatus.OK,
            "회원가입 약관을 조회했습니다."
    ),
    EMAIL_AVAILABLE(
            HttpStatus.OK,
            "사용할 수 있는 이메일입니다."
    ),
    EMAIL_UNAVAILABLE(
            HttpStatus.OK,
            "이미 사용 중인 이메일입니다."
    ),
    SIGNUP_COMPLETED(
            HttpStatus.CREATED,
            "회원가입이 완료되었습니다."
    ),
    PROFILE_RETRIEVED(
            HttpStatus.OK,
            "내 정보를 조회했습니다."
    ),
    PROFILE_UPDATED(
            HttpStatus.OK,
            "사용자 정보를 수정했습니다."
    ),
    USER_SEARCH_RESULT_FOUND(
            HttpStatus.OK,
            "친구 추가 대상 회원을 조회했습니다."
    ),
    USER_SEARCH_RESULT_NOT_FOUND(
            HttpStatus.OK,
            "일치하는 사용자가 없습니다."
    ),
    ONBOARDING_COMPLETED(
            HttpStatus.OK,
            "최초 로그인 설정을 완료했습니다."
    );

    private final HttpStatus status;
    private final String message;

    UserSuccessCode(
            HttpStatus status,
            String message
    ) {
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
