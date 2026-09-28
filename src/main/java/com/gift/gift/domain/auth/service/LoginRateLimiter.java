package com.gift.gift.domain.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.auth.exception.AuthErrorCode;
import com.gift.gift.domain.auth.exception.AuthException;
import com.gift.gift.domain.auth.exception.LoginRateLimitExceededException;
import com.gift.gift.domain.auth.support.LoginRateLimitDecision;
import com.gift.gift.domain.auth.support.RateLimitIdentifierHasher;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginRateLimiter {

    private final LoginRateLimitTransactionService transactionService;
    private final RateLimitIdentifierHasher identifierHasher;

    public void checkIpAttempt(String clientIp) {
        String ipHash = identifierHasher.hashIp(clientIp);

        try {
            LoginRateLimitDecision decision =
                    transactionService.consumeIpToken(ipHash);

            rejectWhenLimited(decision);
        } catch (LoginRateLimitExceededException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw storageUnavailable(exception);
        }
    }

    public void checkEmailAttempt(String email) {
        String emailHash = identifierHasher.hashEmail(email);

        try {
            LoginRateLimitDecision decision =
                    transactionService.inspectEmail(emailHash);

            rejectWhenLimited(decision);
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
                    transactionService.recordEmailFailure(emailHash);

            rejectWhenLimited(decision);
        } catch (LoginRateLimitExceededException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw storageUnavailable(exception);
        }
    }

    public void resetAfterSuccess(String email) {
        String emailHash = identifierHasher.hashEmail(email);

        try {
            transactionService.resetEmailFailure(emailHash);
        } catch (DataAccessException exception) {
            throw storageUnavailable(exception);
        }
    }

    private void rejectWhenLimited(
            LoginRateLimitDecision decision
    ) {
        if (decision.permitted()) {
            return;
        }

        throw new LoginRateLimitExceededException(
                decision.retryAfterSeconds()
        );
    }

    private AuthException storageUnavailable(
            DataAccessException exception
    ) {
        log.error(
                "로그인 요청 제한 저장소를 사용할 수 없습니다.",
                exception
        );

        return new AuthException(
                AuthErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE
        );
    }
}
