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
    private final LoginIpRateLimitRedisService redisService;
    private final LoginEmailFailureLimitRedisService emailRedisService;
    private final RateLimitIdentifierHasher identifierHasher;

    public void checkIpAttempt(String clientIp) {
        String ipHash = identifierHasher.hashIp(clientIp);
        LoginRateLimitDecision decision;

        try {
            decision = redisService.consume(ipHash);
        } catch (DataAccessException redisException) {
            log.warn(
                    "Redis IP 요청 제한을 사용할 수 없어 MySQL fallback을 사용합니다.",
                    redisException
            );

            try {
                decision = transactionService.consumeIpToken(ipHash);
            } catch (DataAccessException databaseException) {
                throw storageUnavailable(databaseException);
            }
        }

        rejectWhenLimited(decision);
    }

    public void checkEmailAttempt(String email) {
        String emailHash = identifierHasher.hashEmail(email);
        LoginRateLimitDecision decision;

        try {
            decision = emailRedisService.inspect(emailHash);
        } catch (DataAccessException redisException) {
            log.warn(
                    "Redis 이메일 로그인 제한을 사용할 수 없어 MySQL fallback을 사용합니다.",
                    redisException
            );

            try {
                decision = transactionService.inspectEmail(emailHash);
            } catch (DataAccessException databaseException) {
                throw storageUnavailable(databaseException);
            }
        }

        rejectWhenLimited(decision);
    }

    public void recordFailure(String email) {
        String emailHash = identifierHasher.hashEmail(email);
        LoginRateLimitDecision decision;

        try {
            decision = emailRedisService.recordFailure(emailHash);
        } catch (DataAccessException redisException) {
            log.warn(
                    "Redis 이메일 실패 기록을 사용할 수 없어 MySQL fallback을 사용합니다.",
                    redisException
            );

            try {
                decision = transactionService.recordEmailFailure(emailHash);
            } catch (DataAccessException databaseException) {
                throw storageUnavailable(databaseException);
            }
        }

        rejectWhenLimited(decision);
    }

    public void resetAfterSuccess(String email) {
        String emailHash = identifierHasher.hashEmail(email);

        try {
            emailRedisService.reset(emailHash);
        } catch (DataAccessException redisException) {
            log.warn(
                    "Redis 이메일 실패 상태를 초기화할 수 없어 MySQL fallback을 사용합니다.",
                    redisException
            );

            try {
                transactionService.resetEmailFailure(emailHash);
            } catch (DataAccessException databaseException) {
                throw storageUnavailable(databaseException);
            }
        }
    }

    private void rejectWhenLimited(LoginRateLimitDecision decision) {

        if (decision.permitted()) {
            return;
        }

        throw new LoginRateLimitExceededException(decision.retryAfterSeconds());
    }

    private AuthException storageUnavailable(DataAccessException exception) {

        log.error(
                "로그인 요청 제한 저장소를 사용할 수 없습니다.",
                exception
        );

        return new AuthException(AuthErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE);
    }
}
