package com.gift.gift.domain.user.exception;

import java.util.List;

import com.gift.gift.global.exception.ValidationDetail;

public class SignupPolicyViolationException extends RuntimeException {

    private final List<ValidationDetail> details;

    public SignupPolicyViolationException(List<ValidationDetail> details) {
        super("Signup policy requirements were not met");

        this.details = List.copyOf(details);

        if (this.details.isEmpty()) {
            throw new IllegalArgumentException("details must not be empty");
        }
    }

    public List<ValidationDetail> getDetails() {
        return details;
    }
}
