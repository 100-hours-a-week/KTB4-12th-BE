package com.gift.gift.domain.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.user.exception.LoginRateLimitExceededException;
import com.gift.gift.domain.user.support.LoginRateLimitDecision;
import com.gift.gift.domain.user.support.RateLimitIdentifierHasher;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginRateLimiter {

    private final LoginRateLimitTransactionService transactionService;
    private final RateLimitIdentifierHasher identifierHasher;

    public void checkAttempt(
            String clientIp,
            String email
    ) {
        String ipHash = identifierHasher.hashIp(clientIp);
        String emailHash = identifierHasher.hashEmail(email);

        try {
            LoginRateLimitDecision ipDecision =
                    transactionService.consumeIpToken(ipHash);

            LoginRateLimitDecision emailDecision =
                    transactionService.inspectEmail(emailHash);

            rejectWhenLimited(
                    ipDecision,
                    emailDecision
            );
        } catch (LoginRateLimitExceededException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw storageUnavailable(exception);
        }
    }

    public void recordFailure(String email) {
        String emailHash = identifierHasher.hashEmail(email);

        try {
            LoginRateLimitDecision decision =
                    transactionService.recordEmailFailure(
                            emailHash
                    );

            if (!decision.permitted()) {
                throw new LoginRateLimitExceededException(
                        decision.retryAfterSeconds()
                );
            }
        } catch (LoginRateLimitExceededException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw storageUnavailable(exception);
        }
    }

    public void resetAfterSuccess(String email) {
        String emailHash = identifierHasher.hashEmail(email);

        try {
            transactionService.resetEmailFailure(
                    emailHash
            );
        } catch (DataAccessException exception) {
            throw storageUnavailable(exception);
        }
    }

    private void rejectWhenLimited(
            LoginRateLimitDecision ipDecision,
            LoginRateLimitDecision emailDecision
    ) {
        if (ipDecision.permitted()
                && emailDecision.permitted()) {
            return;
        }

        long retryAfterSeconds = Math.max(
                ipDecision.retryAfterSeconds(),
                emailDecision.retryAfterSeconds()
        );

        throw new LoginRateLimitExceededException(
                retryAfterSeconds
        );
    }

    private BusinessException storageUnavailable(
            DataAccessException exception
    ) {
        log.error(
                "Login rate limit storage is unavailable",
                exception
        );

        return new BusinessException(
                ErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE
        );
    }
}
