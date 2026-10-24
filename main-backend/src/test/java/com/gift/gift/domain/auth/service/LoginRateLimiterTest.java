package com.gift.gift.domain.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import com.gift.gift.domain.auth.exception.LoginRateLimitExceededException;
import com.gift.gift.domain.auth.support.LoginRateLimitDecision;
import com.gift.gift.domain.auth.support.RateLimitIdentifierHasher;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginRateLimiterTest {

    private static final String CLIENT_IP =
            "203.0.113.10";

    private static final String EMAIL =
            "user@example.com";

    private static final String IP_HASH =
            "a".repeat(64);

    private static final String EMAIL_HASH =
            "b".repeat(64);

    @Mock
    private LoginRateLimitTransactionService transactionService;

    @Mock
    private LoginIpRateLimitRedisService redisService;

    @Mock
    private LoginEmailFailureLimitRedisService emailRedisService;

    @Mock
    private RateLimitIdentifierHasher identifierHasher;

    private LoginRateLimiter loginRateLimiter;

    @BeforeEach
    void setUp() {
        loginRateLimiter = new LoginRateLimiter(
                transactionService,
                redisService,
                emailRedisService,
                identifierHasher
        );
    }

    @Test
    @DisplayName("IP와 이메일 Redis 제한을 통과하면 로그인을 계속한다")
    void checkAttempts_allowWhenBothLimitsPermit() {
        stubIdentifiers();

        when(redisService.consume(IP_HASH))
                .thenReturn(LoginRateLimitDecision.permit());

        when(emailRedisService.inspect(EMAIL_HASH))
                .thenReturn(LoginRateLimitDecision.permit());

        assertDoesNotThrow(
                () -> {
                    loginRateLimiter.checkIpAttempt(CLIENT_IP);
                    loginRateLimiter.checkEmailAttempt(EMAIL);
                }
        );

        verify(transactionService, never())
                .consumeIpToken(IP_HASH);
        verify(transactionService, never())
                .inspectEmail(EMAIL_HASH);
    }

    @Test
    @DisplayName("Redis가 IP 제한을 반환하면 MySQL fallback 없이 요청을 제한한다")
    void checkIpAttempt_rejectsFromRedisWithoutDatabaseFallback() {
        givenIpHash();

        when(redisService.consume(IP_HASH))
                .thenReturn(LoginRateLimitDecision.reject(6));

        LoginRateLimitExceededException exception =
                assertThrows(
                        LoginRateLimitExceededException.class,
                        () -> loginRateLimiter.checkIpAttempt(CLIENT_IP)
                );

        assertEquals(6, exception.getRetryAfterSeconds());
        assertEquals(
                ErrorCode.TOO_MANY_REQUESTS,
                exception.getErrorCode()
        );

        verify(transactionService, never())
                .consumeIpToken(IP_HASH);
    }

    @Test
    @DisplayName("IP Redis 장애 시 MySQL Token Bucket으로 폴백한다")
    void checkIpAttempt_usesDatabaseWhenRedisIsUnavailable() {
        givenIpHash();

        when(redisService.consume(IP_HASH))
                .thenThrow(
                        new DataAccessResourceFailureException(
                                "Redis unavailable"
                        )
                );

        when(transactionService.consumeIpToken(IP_HASH))
                .thenReturn(LoginRateLimitDecision.permit());

        assertDoesNotThrow(
                () -> loginRateLimiter.checkIpAttempt(CLIENT_IP)
        );

        verify(transactionService).consumeIpToken(IP_HASH);
    }

    @Test
    @DisplayName("IP MySQL fallback이 제한하면 429를 반환한다")
    void checkIpAttempt_rejectsWhenDatabaseFallbackIsLimited() {
        givenIpHash();

        when(redisService.consume(IP_HASH))
                .thenThrow(
                        new DataAccessResourceFailureException(
                                "Redis unavailable"
                        )
                );

        when(transactionService.consumeIpToken(IP_HASH))
                .thenReturn(LoginRateLimitDecision.reject(5));

        LoginRateLimitExceededException exception =
                assertThrows(
                        LoginRateLimitExceededException.class,
                        () -> loginRateLimiter.checkIpAttempt(CLIENT_IP)
                );

        assertEquals(5, exception.getRetryAfterSeconds());
        assertEquals(
                ErrorCode.TOO_MANY_REQUESTS,
                exception.getErrorCode()
        );
    }

    @Test
    @DisplayName("Redis가 복구되면 다음 IP 요청부터 Redis를 사용한다")
    void checkIpAttempt_returnsToRedisAfterRecovery() {
        givenIpHash();

        when(redisService.consume(IP_HASH))
                .thenThrow(
                        new DataAccessResourceFailureException(
                                "Redis unavailable"
                        )
                )
                .thenReturn(LoginRateLimitDecision.permit());

        when(transactionService.consumeIpToken(IP_HASH))
                .thenReturn(LoginRateLimitDecision.permit());

        loginRateLimiter.checkIpAttempt(CLIENT_IP);
        loginRateLimiter.checkIpAttempt(CLIENT_IP);

        verify(redisService, times(2)).consume(IP_HASH);
        verify(transactionService).consumeIpToken(IP_HASH);
    }

    @Test
    @DisplayName("Redis가 이메일을 차단하면 MySQL 조회 없이 요청을 제한한다")
    void checkEmailAttempt_rejectsFromRedisWithoutDatabaseFallback() {
        givenEmailHash();

        when(emailRedisService.inspect(EMAIL_HASH))
                .thenReturn(LoginRateLimitDecision.reject(30));

        LoginRateLimitExceededException exception =
                assertThrows(
                        LoginRateLimitExceededException.class,
                        () -> loginRateLimiter.checkEmailAttempt(EMAIL)
                );

        assertEquals(30, exception.getRetryAfterSeconds());
        verify(transactionService, never())
                .inspectEmail(EMAIL_HASH);
    }

    @Test
    @DisplayName("이메일 제한 Redis 장애 시 MySQL 검사로 폴백한다")
    void checkEmailAttempt_usesDatabaseWhenRedisIsUnavailable() {
        givenEmailHash();

        when(emailRedisService.inspect(EMAIL_HASH))
                .thenThrow(
                        new DataAccessResourceFailureException(
                                "Redis unavailable"
                        )
                );

        when(transactionService.inspectEmail(EMAIL_HASH))
                .thenReturn(LoginRateLimitDecision.permit());

        assertDoesNotThrow(
                () -> loginRateLimiter.checkEmailAttempt(EMAIL)
        );

        verify(transactionService).inspectEmail(EMAIL_HASH);
    }

    @Test
    @DisplayName("이메일 MySQL fallback이 차단 중이면 429를 반환한다")
    void checkEmailAttempt_rejectsWhenDatabaseFallbackIsLimited() {
        givenEmailHash();

        when(emailRedisService.inspect(EMAIL_HASH))
                .thenThrow(
                        new DataAccessResourceFailureException(
                                "Redis unavailable"
                        )
                );

        when(transactionService.inspectEmail(EMAIL_HASH))
                .thenReturn(LoginRateLimitDecision.reject(30));

        LoginRateLimitExceededException exception =
                assertThrows(
                        LoginRateLimitExceededException.class,
                        () -> loginRateLimiter.checkEmailAttempt(EMAIL)
                );

        assertEquals(30, exception.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("이메일 제한 검사에서 Redis와 MySQL이 모두 실패하면 503을 반환한다")
    void checkEmailAttempt_returnsServiceUnavailableWhenBothStoresFail() {
        givenEmailHash();

        when(emailRedisService.inspect(EMAIL_HASH))
                .thenThrow(
                        new DataAccessResourceFailureException(
                                "Redis unavailable"
                        )
                );

        when(transactionService.inspectEmail(EMAIL_HASH))
                .thenThrow(
                        new DataAccessResourceFailureException(
                                "MySQL unavailable"
                        )
                );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> loginRateLimiter.checkEmailAttempt(EMAIL)
        );

        assertEquals(
                ErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE,
                exception.getErrorCode()
        );

        verify(emailRedisService).inspect(EMAIL_HASH);
        verify(transactionService).inspectEmail(EMAIL_HASH);
    }

    @Test
    @DisplayName("IP 제한이 거부되면 이메일 제한 검사는 실행하지 않는다")
    void separatedChecks_stopBeforeEmailInspectionWhenIpRejects() {
        givenIpHash();

        when(redisService.consume(IP_HASH))
                .thenReturn(LoginRateLimitDecision.reject(6));

        LoginRateLimitExceededException exception =
                assertThrows(
                        LoginRateLimitExceededException.class,
                        () -> {
                            loginRateLimiter.checkIpAttempt(CLIENT_IP);
                            loginRateLimiter.checkEmailAttempt(EMAIL);
                        }
                );

        assertEquals(6, exception.getRetryAfterSeconds());
        verify(emailRedisService, never())
                .inspect(EMAIL_HASH);
        verify(transactionService, never())
                .inspectEmail(EMAIL_HASH);
    }

    @Test
    @DisplayName("Redis 기록 결과가 제한이면 제한 예외를 발생시킨다")
    void recordFailure_rejectsWhenEmailIsAlreadyBlocked() {
        givenEmailHash();

        when(emailRedisService.recordFailure(EMAIL_HASH))
                .thenReturn(LoginRateLimitDecision.reject(29));

        LoginRateLimitExceededException exception =
                assertThrows(
                        LoginRateLimitExceededException.class,
                        () -> loginRateLimiter.recordFailure(EMAIL)
                );

        assertEquals(29, exception.getRetryAfterSeconds());
        verify(transactionService, never())
                .recordEmailFailure(EMAIL_HASH);
    }

    @Test
    @DisplayName("실패 기록 중 Redis 장애가 발생하면 MySQL에 기록한다")
    void recordFailure_usesDatabaseWhenRedisIsUnavailable() {
        givenEmailHash();

        when(emailRedisService.recordFailure(EMAIL_HASH))
                .thenThrow(
                        new DataAccessResourceFailureException(
                                "Redis unavailable"
                        )
                );

        when(transactionService.recordEmailFailure(EMAIL_HASH))
                .thenReturn(LoginRateLimitDecision.permit());

        assertDoesNotThrow(
                () -> loginRateLimiter.recordFailure(EMAIL)
        );

        verify(transactionService).recordEmailFailure(EMAIL_HASH);
    }

    @Test
    @DisplayName("로그인 성공 시 Redis 이메일 실패 상태를 삭제한다")
    void resetAfterSuccess_deletesRedisEmailFailureState() {
        givenEmailHash();

        loginRateLimiter.resetAfterSuccess(EMAIL);

        verify(emailRedisService).reset(EMAIL_HASH);
        verify(transactionService, never())
                .resetEmailFailure(EMAIL_HASH);
    }

    @Test
    @DisplayName("성공 초기화 중 Redis 장애가 발생하면 MySQL 상태를 삭제한다")
    void resetAfterSuccess_usesDatabaseWhenRedisIsUnavailable() {
        givenEmailHash();

        doThrow(
                new DataAccessResourceFailureException(
                        "Redis unavailable"
                )
        ).when(emailRedisService)
                .reset(EMAIL_HASH);

        loginRateLimiter.resetAfterSuccess(EMAIL);

        verify(transactionService)
                .resetEmailFailure(EMAIL_HASH);
    }

    @Test
    @DisplayName("이메일 초기화에서 Redis와 MySQL이 모두 실패하면 503을 반환한다")
    void resetAfterSuccess_returnsServiceUnavailableWhenBothStoresFail() {
        givenEmailHash();

        doThrow(
                new DataAccessResourceFailureException(
                        "Redis unavailable"
                )
        ).when(emailRedisService)
                .reset(EMAIL_HASH);

        doThrow(
                new DataAccessResourceFailureException(
                        "MySQL unavailable"
                )
        ).when(transactionService)
                .resetEmailFailure(EMAIL_HASH);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> loginRateLimiter.resetAfterSuccess(EMAIL)
        );

        assertEquals(
                ErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE,
                exception.getErrorCode()
        );
    }

    @Test
    @DisplayName("IP 제한 검사에서 Redis와 MySQL이 모두 실패하면 503을 반환한다")
    void checkIpAttempt_returnsServiceUnavailableWhenBothStoresFail() {
        givenIpHash();

        when(redisService.consume(IP_HASH))
                .thenThrow(
                        new DataAccessResourceFailureException(
                                "Redis unavailable"
                        )
                );

        when(transactionService.consumeIpToken(IP_HASH))
                .thenThrow(
                        new DataAccessResourceFailureException(
                                "MySQL unavailable"
                        )
                );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> loginRateLimiter.checkIpAttempt(CLIENT_IP)
        );

        assertEquals(
                ErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE,
                exception.getErrorCode()
        );

        verify(redisService).consume(IP_HASH);
        verify(transactionService).consumeIpToken(IP_HASH);
    }

    @Test
    @DisplayName("예상하지 못한 Redis 프로그래밍 오류는 MySQL fallback으로 바꾸지 않는다")
    void checkEmailAttempt_doesNotFallbackForProgrammingError() {
        givenEmailHash();

        IllegalStateException failure =
                new IllegalStateException("unexpected state");

        when(emailRedisService.inspect(EMAIL_HASH))
                .thenThrow(failure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> loginRateLimiter.checkEmailAttempt(EMAIL)
        );

        assertSame(failure, thrown);
        verify(transactionService, never())
                .inspectEmail(EMAIL_HASH);
    }

    private void givenIpHash() {
        when(identifierHasher.hashIp(CLIENT_IP))
                .thenReturn(IP_HASH);
    }

    private void givenEmailHash() {
        when(identifierHasher.hashEmail(EMAIL))
                .thenReturn(EMAIL_HASH);
    }

    private void stubIdentifiers() {
        givenIpHash();
        givenEmailHash();
    }
}
