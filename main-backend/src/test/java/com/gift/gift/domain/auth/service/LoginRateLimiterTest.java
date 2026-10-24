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
    private RateLimitIdentifierHasher identifierHasher;

    private LoginRateLimiter loginRateLimiter;

    @BeforeEach
    void setUp() {
        loginRateLimiter = new LoginRateLimiter(
                transactionService,
                redisService,
                identifierHasher
        );
    }

    @Test
    @DisplayName("Redis와 이메일 제한을 통과하면 로그인 처리를 계속한다")
    void checkAttempts_allowWhenBothLimitsPermit() {
        stubIdentifiers();

        when(redisService.consume(IP_HASH))
                .thenReturn(LoginRateLimitDecision.permit());

        when(transactionService.inspectEmail(EMAIL_HASH))
                .thenReturn(LoginRateLimitDecision.permit());

        assertDoesNotThrow(
                () -> {
                    loginRateLimiter.checkIpAttempt(CLIENT_IP);
                    loginRateLimiter.checkEmailAttempt(EMAIL);
                }
        );

        verify(transactionService, never())
                .consumeIpToken(IP_HASH);
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
    @DisplayName("Redis 오류 시 MySQL Token Bucket으로 제한을 이어간다")
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
    @DisplayName("Redis 장애 중 MySQL Token Bucket이 제한하면 429를 반환한다")
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
    @DisplayName("Redis가 복구되면 다음 요청부터 Redis를 즉시 사용한다")
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
    @DisplayName("이메일이 차단 중이면 기존 이메일 재시도 시간으로 요청을 제한한다")
    void checkEmailAttempt_rejectsWhenEmailLimitRejects() {
        when(identifierHasher.hashEmail(EMAIL))
                .thenReturn(EMAIL_HASH);

        when(transactionService.inspectEmail(EMAIL_HASH))
                .thenReturn(LoginRateLimitDecision.reject(30));

        LoginRateLimitExceededException exception =
                assertThrows(
                        LoginRateLimitExceededException.class,
                        () -> loginRateLimiter.checkEmailAttempt(EMAIL)
                );

        assertEquals(30, exception.getRetryAfterSeconds());
        verify(redisService, never()).consume(IP_HASH);
    }

    @Test
    @DisplayName("IP 제한이 거부되면 이후 이메일 제한 검사는 실행하지 않는다")
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
        verify(transactionService, never())
                .inspectEmail(EMAIL_HASH);
    }

    @Test
    @DisplayName("이메일 실패 기록 결과가 제한이면 요청 제한 예외를 발생시킨다")
    void recordFailure_rejectsWhenEmailIsAlreadyBlocked() {
        when(identifierHasher.hashEmail(EMAIL))
                .thenReturn(EMAIL_HASH);

        when(transactionService.recordEmailFailure(EMAIL_HASH))
                .thenReturn(LoginRateLimitDecision.reject(29));

        LoginRateLimitExceededException exception =
                assertThrows(
                        LoginRateLimitExceededException.class,
                        () -> loginRateLimiter.recordFailure(EMAIL)
                );

        assertEquals(29, exception.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("로그인 성공 시 정규화 이메일의 실패 상태를 초기화한다")
    void resetAfterSuccess_deletesEmailFailureState() {
        when(identifierHasher.hashEmail(EMAIL))
                .thenReturn(EMAIL_HASH);

        loginRateLimiter.resetAfterSuccess(EMAIL);

        verify(transactionService)
                .resetEmailFailure(EMAIL_HASH);
    }

    @Test
    @DisplayName("Redis와 MySQL이 모두 실패하면 인증 서비스 일시 불가 오류를 반환한다")
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
    @DisplayName("이메일 실패 상태 초기화 중 저장소 장애는 503으로 변환한다")
    void resetAfterSuccess_convertsDataAccessFailureToServiceUnavailable() {
        when(identifierHasher.hashEmail(EMAIL))
                .thenReturn(EMAIL_HASH);

        doThrow(
                new DataAccessResourceFailureException(
                        "database unavailable"
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
    @DisplayName("예상하지 못한 Redis 프로그래밍 오류는 MySQL 장애 fallback으로 바꾸지 않는다")
    void checkIpAttempt_doesNotFallbackForProgrammingError() {
        givenIpHash();

        IllegalStateException failure =
                new IllegalStateException("unexpected state");

        when(redisService.consume(IP_HASH))
                .thenThrow(failure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> loginRateLimiter.checkIpAttempt(CLIENT_IP)
        );

        assertSame(failure, thrown);
        verify(transactionService, never())
                .consumeIpToken(IP_HASH);
    }

    private void givenIpHash() {
        when(identifierHasher.hashIp(CLIENT_IP))
                .thenReturn(IP_HASH);
    }

    private void stubIdentifiers() {
        givenIpHash();

        when(identifierHasher.hashEmail(EMAIL))
                .thenReturn(EMAIL_HASH);
    }
}
