package com.gift.gift.domain.user.dto.response;

import com.gift.gift.domain.user.entity.Term;

public record SignupTermResponse(
        Long termId,
        String termCode,
        String title,
        int version,
        boolean isRequired,
        String content
) {

    public static SignupTermResponse from(Term term) {
        return new SignupTermResponse(
                term.getId(),
                term.getTermCode(),
                term.getTitle(),
                term.getVersion(),
                term.isRequired(),
                term.getContent()
        );
    }
}
