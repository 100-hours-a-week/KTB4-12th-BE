package com.gift.gift.domain.user.exception;

public class SignupTermsConfigurationException extends RuntimeException {

    public SignupTermsConfigurationException() {
        super("No current required signup terms are configured");
    }
}
