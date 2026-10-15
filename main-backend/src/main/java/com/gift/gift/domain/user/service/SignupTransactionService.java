package com.gift.gift.domain.user.service;

import java.time.LocalDate;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.credit.service.SignupCreditService;
import com.gift.gift.domain.user.dto.request.SignupRequest;
import com.gift.gift.domain.user.entity.TermConsent;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.exception.UserErrorCode;
import com.gift.gift.domain.user.exception.UserException;
import com.gift.gift.domain.user.repository.TermConsentRepository;
import com.gift.gift.domain.user.repository.TermRepository;
import com.gift.gift.domain.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class SignupTransactionService {

    private final UserRepository userRepository;
    private final TermRepository termRepository;
    private final TermConsentRepository termConsentRepository;
    private final SignupCreditService signupCreditService;

    @Transactional
    public User create(SignupRequest request, LocalDate birth, String passwordHash) {

        User user = new User(
                request.email(),
                passwordHash,
                request.name(),
                birth
        );

        User savedUser = saveUser(user);

        List<TermConsent> consents =
                request.termConsents().stream()
                        .map(consent -> new TermConsent(
                                savedUser,
                                termRepository.getReferenceById(
                                        consent.termId()
                                ),
                                consent.isAgreed()
                        ))
                        .toList();

        termConsentRepository.saveAllAndFlush(consents);
        signupCreditService.grant(savedUser.getId());

        return savedUser;
    }

    private User saveUser(User user) {
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            if (isEmailUniqueViolation(exception)) {
                throw new UserException(
                        UserErrorCode.EMAIL_ALREADY_IN_USE
                );
            }

            throw exception;
        }
    }

    private boolean isEmailUniqueViolation(Throwable exception) {

        Throwable current = exception;

        while (current != null) {
            if (current instanceof
                    org.hibernate.exception.ConstraintViolationException violation) {
                String constraintName =
                        violation.getConstraintName();

                if (constraintName == null) {
                    return false;
                }

                String normalizedName =
                        constraintName.replace("`", "");

                int separatorIndex =
                        normalizedName.lastIndexOf('.');

                if (separatorIndex >= 0) {
                    normalizedName = normalizedName.substring(
                            separatorIndex + 1
                    );
                }

                return "uk_users_email"
                        .equalsIgnoreCase(normalizedName)
                        && violation.getSQLException()
                        .getErrorCode() == 1062;
            }

            current = current.getCause();
        }

        return false;
    }
}
