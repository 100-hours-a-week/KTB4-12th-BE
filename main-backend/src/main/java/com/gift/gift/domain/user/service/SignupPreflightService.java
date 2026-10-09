package com.gift.gift.domain.user.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.user.dto.response.EmailAvailabilityResponse;
import com.gift.gift.domain.user.dto.response.SignupTermResponse;
import com.gift.gift.domain.user.dto.response.SignupTermsResponse;
import com.gift.gift.domain.user.entity.Term;
import com.gift.gift.domain.user.exception.UserErrorCode;
import com.gift.gift.domain.user.exception.UserException;
import com.gift.gift.domain.user.repository.TermRepository;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.domain.user.support.EmailNormalizer;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SignupPreflightService {

    private final TermRepository termRepository;
    private final UserRepository userRepository;

    public SignupTermsResponse getSignupTerms() {
        List<Term> terms = termRepository.findCurrentTerms();

        if (terms.stream().noneMatch(Term::isRequired)) {
            throw new UserException(
                    UserErrorCode.SIGNUP_TERMS_NOT_FOUND
            );
        }

        List<SignupTermResponse> responses = terms.stream()
                .map(SignupTermResponse::from)
                .toList();

        return SignupTermsResponse.from(responses);
    }

    public EmailAvailabilityResponse checkEmailAvailability(
            String email
    ) {
        String normalizedEmail = EmailNormalizer.normalize(email);

        boolean available = !userRepository.existsByEmail(
                normalizedEmail
        );

        return EmailAvailabilityResponse.from(available);
    }
}
