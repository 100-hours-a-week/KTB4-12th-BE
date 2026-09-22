package com.gift.gift.domain.user.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.user.dto.request.SignupRequest;
import com.gift.gift.domain.user.dto.request.SignupTermConsentRequest;
import com.gift.gift.domain.user.dto.response.SignupResponse;
import com.gift.gift.domain.user.entity.Term;
import com.gift.gift.domain.user.entity.TermConsent;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.exception.SignupTermsConfigurationException;
import com.gift.gift.domain.user.exception.UserErrorCode;
import com.gift.gift.domain.user.exception.UserException;
import com.gift.gift.domain.user.repository.TermConsentRepository;
import com.gift.gift.domain.user.repository.TermRepository;
import com.gift.gift.domain.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class SignupService {

    private final UserRepository userRepository;
    private final TermRepository termRepository;
    private final TermConsentRepository termConsentRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        LocalDate birth = LocalDate.parse(request.birth());

        List<Term> requiredTerms = termRepository.findCurrentRequiredTerms();

        if (requiredTerms.isEmpty()) {
            throw new SignupTermsConfigurationException();
        }

        validateTermConsents(requiredTerms, request.termConsents());

        if (userRepository.existsByEmail(request.email())) {
            throw new UserException(UserErrorCode.EMAIL_ALREADY_IN_USE);
        }

        String passwordHash = passwordEncoder.encode(request.password());

        User user = new User(
                request.email(),
                passwordHash,
                request.name(),
                birth
        );

        User savedUser = saveUser(user);

        List<TermConsent> consents = requiredTerms.stream()
                .map(term -> new TermConsent(savedUser, term, true))
                .toList();

        termConsentRepository.saveAllAndFlush(consents);

        return SignupResponse.from(savedUser);
    }

    private void validateTermConsents(
            List<Term> requiredTerms,
            List<SignupTermConsentRequest> requestedConsents
    ) {

        Map<Long, Term> currentTermsById = requiredTerms.stream()
                .collect(Collectors.toMap(Term::getId, Function.identity()));

        for (SignupTermConsentRequest consent : requestedConsents) {
            Term currentTerm = currentTermsById.get(consent.termId());

            if (currentTerm == null
                    || currentTerm.getVersion() != consent.version()) {
                throw new UserException(UserErrorCode.INVALID_TERM_VERSION);
            }
        }

        Map<Long, SignupTermConsentRequest> requestedById =
                requestedConsents.stream()
                        .collect(Collectors.toMap(
                                SignupTermConsentRequest::termId,
                                Function.identity()
                        ));

        for (Term term : requiredTerms) {
            SignupTermConsentRequest consent = requestedById.get(term.getId());

            if (consent == null || !Boolean.TRUE.equals(consent.isAgreed())) {
                throw new UserException(UserErrorCode.REQUIRED_TERMS_NOT_AGREED);
            }
        }
    }

    private User saveUser(User user) {
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            if (isEmailUniqueViolation(exception)) {
                throw new UserException(UserErrorCode.EMAIL_ALREADY_IN_USE);
            }

            throw exception;
        }
    }

    private boolean isEmailUniqueViolation(Throwable exception) {
        Throwable current = exception;

        while (current != null) {
            if (current instanceof
                    org.hibernate.exception.ConstraintViolationException violation) {
                String constraintName = violation.getConstraintName();

                if (constraintName == null) {
                    return false;
                }

                String normalizedName = constraintName.replace("`", "");
                int separatorIndex = normalizedName.lastIndexOf('.');

                if (separatorIndex >= 0) {
                    normalizedName = normalizedName.substring(separatorIndex + 1);
                }

                return "uk_users_email".equalsIgnoreCase(normalizedName)
                        && violation.getSQLException().getErrorCode() == 1062;
            }

            current = current.getCause();
        }

        return false;
    }
}
