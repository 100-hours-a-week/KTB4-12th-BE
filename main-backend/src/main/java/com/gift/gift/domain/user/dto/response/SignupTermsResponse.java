package com.gift.gift.domain.user.dto.response;

import java.util.List;

public record SignupTermsResponse(
        List<SignupTermResponse> terms
) {

    public SignupTermsResponse {
        terms = List.copyOf(terms);
    }

    public static SignupTermsResponse from(
            List<SignupTermResponse> terms
    ) {
        return new SignupTermsResponse(terms);
    }
}
