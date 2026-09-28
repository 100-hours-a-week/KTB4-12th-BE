package com.gift.gift.domain.user.exception;

public class SignupTermsConfigurationException extends RuntimeException {

    public SignupTermsConfigurationException() {
        super("현재 적용할 필수 회원가입 약관이 설정되지 않았습니다.");
    }
}
