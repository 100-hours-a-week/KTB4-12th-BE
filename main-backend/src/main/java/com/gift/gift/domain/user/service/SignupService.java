package com.gift.gift.domain.user.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.auth.service.EmailVerificationService;
import com.gift.gift.domain.user.dto.request.SignupRequest;
import com.gift.gift.domain.user.dto.request.SignupTermConsentRequest;
import com.gift.gift.domain.user.dto.response.SignupResponse;
import com.gift.gift.domain.user.entity.Term;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.exception.SignupTermsConfigurationException;
import com.gift.gift.domain.user.exception.UserErrorCode;
import com.gift.gift.domain.user.exception.UserException;
import com.gift.gift.domain.user.repository.TermRepository;
import com.gift.gift.domain.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class SignupService {

    private final UserRepository userRepository;
    private final TermRepository termRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final SignupTransactionService transactionService;

    public SignupResponse signup(SignupRequest request) {
        LocalDate birth = LocalDate.parse(request.birth());

        List<Term> currentTerms = termRepository.findCurrentTerms();
        List<Term> requiredTerms = currentTerms.stream()
                .filter(Term::isRequired)
                .toList();

        if (requiredTerms.isEmpty()) {
            throw new SignupTermsConfigurationException();
        }

        Map<Long, Term> currentTermsById = currentTerms.stream()
                .collect(Collectors.toMap(
                        Term::getId,
                        Function.identity()
                ));

        validateTermConsents(
                currentTermsById,
                requiredTerms,
                request.termConsents()
        );

        if (userRepository.existsByEmail(request.email())) {
            throw new UserException(UserErrorCode.EMAIL_ALREADY_IN_USE);
        }

        emailVerificationService.consumeRegistrationToken(
                request.email(),
                request.emailVerificationToken()
        );

        String passwordHash = passwordEncoder.encode(request.password());

        User user = transactionService.create(
                request,
                birth,
                passwordHash
        );

        return SignupResponse.from(user);
    }

    private void validateTermConsents(
            Map<Long, Term> currentTermsById,
            List<Term> requiredTerms,
            List<SignupTermConsentRequest> requestedConsents
    ) {
        for (SignupTermConsentRequest consent : requestedConsents) {
            Term currentTerm =
                    currentTermsById.get(consent.termId());

            if (currentTerm == null
                    || currentTerm.getVersion() != consent.version()) {
                throw new UserException(
                        UserErrorCode.INVALID_TERM_VERSION
                );
            }
        }

        Map<Long, SignupTermConsentRequest> requestedById =
                requestedConsents.stream()
                        .collect(Collectors.toMap(
                                SignupTermConsentRequest::termId,
                                Function.identity()
                        ));

        for (Term term : requiredTerms) {
            SignupTermConsentRequest consent =
                    requestedById.get(term.getId());

            if (consent == null
                    || !Boolean.TRUE.equals(consent.isAgreed())) {
                throw new UserException(
                        UserErrorCode.REQUIRED_TERMS_NOT_AGREED
                );
            }
        }
    }
}
