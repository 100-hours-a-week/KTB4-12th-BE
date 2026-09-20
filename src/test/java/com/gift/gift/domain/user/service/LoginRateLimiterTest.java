package com.gift.gift.domain.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import com.gift.gift.domain.user.exception.LoginRateLimitExceededException;
import com.gift.gift.domain.user.support.LoginRateLimitDecision;
import com.gift.gift.domain.user.support.RateLimitIdentifierHasher;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
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
    private RateLimitIdentifierHasher identifierHasher;

    private LoginRateLimiter loginRateLimiter;

    @BeforeEach
    void setUp() {
        loginRateLimiter = new LoginRateLimiter(
                transactionService,
                identifierHasher
        );
    }

    @Test
    @DisplayName("IP와 이메일 제한을 모두 통과하면 로그인을 계속 처리한다")
    void checkAttempt_allowsWhenBothLimitsPermit() {
        stubIdentifiers();

        when(transactionService.consumeIpToken(IP_HASH))
                .thenReturn(LoginRateLimitDecision.permit());

        when(transactionService.inspectEmail(EMAIL_HASH))
                .thenReturn(LoginRateLimitDecision.permit());

        assertDoesNotThrow(
                () -> loginRateLimiter.checkAttempt(
                        CLIENT_IP,
                        EMAIL
                )
        );
    }

    @Test
    @DisplayName("IP 토큰이 없으면 IP의 재시도 시간으로 요청을 제한한다")
    void checkAttempt_rejectsWhenIpLimitRejects() {
        stubIdentifiers();

        when(transactionService.consumeIpToken(IP_HASH))
                .thenReturn(
                        LoginRateLimitDecision.reject(6)
                );

        when(transactionService.inspectEmail(EMAIL_HASH))
                .thenReturn(
                        LoginRateLimitDecision.permit()
                );

        LoginRateLimitExceededException exception =
                assertThrows(
                        LoginRateLimitExceededException.class,
                        () -> loginRateLimiter.checkAttempt(
                                CLIENT_IP,
                                EMAIL
                        )
                );

        assertEquals(6, exception.getRetryAfterSeconds());
        assertEquals(
                ErrorCode.TOO_MANY_REQUESTS,
                exception.getErrorCode()
        );
    }

    @Test
    @DisplayName("이메일이 차단 중이면 이메일의 재시도 시간으로 요청을 제한한다")
    void checkAttempt_rejectsWhenEmailLimitRejects() {
        stubIdentifiers();

        when(transactionService.consumeIpToken(IP_HASH))
                .thenReturn(
                        LoginRateLimitDecision.permit()
                );

        when(transactionService.inspectEmail(EMAIL_HASH))
                .thenReturn(
                        LoginRateLimitDecision.reject(30)
                );

        LoginRateLimitExceededException exception =
                assertThrows(
                        LoginRateLimitExceededException.class,
                        () -> loginRateLimiter.checkAttempt(
                                CLIENT_IP,
                                EMAIL
                        )
                );

        assertEquals(30, exception.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("IP와 이메일이 모두 제한되면 더 긴 재시도 시간을 반환한다")
    void checkAttempt_usesLongerRetryAfterWhenBothReject() {
        stubIdentifiers();

        when(transactionService.consumeIpToken(IP_HASH))
                .thenReturn(
                        LoginRateLimitDecision.reject(6)
                );

        when(transactionService.inspectEmail(EMAIL_HASH))
                .thenReturn(
                        LoginRateLimitDecision.reject(120)
                );

        LoginRateLimitExceededException exception =
                assertThrows(
                        LoginRateLimitExceededException.class,
                        () -> loginRateLimiter.checkAttempt(
                                CLIENT_IP,
                                EMAIL
                        )
                );

        assertEquals(
                120,
                exception.getRetryAfterSeconds()
        );
    }

    @Test
    @DisplayName("이메일 실패 기록 결과가 제한이면 요청 제한 예외를 발생시킨다")
    void recordFailure_rejectsWhenEmailIsAlreadyBlocked() {
        when(identifierHasher.hashEmail(EMAIL))
                .thenReturn(EMAIL_HASH);

        when(transactionService.recordEmailFailure(EMAIL_HASH))
                .thenReturn(
                        LoginRateLimitDecision.reject(29)
                );

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
    @DisplayName("요청 제한 저장소 장애는 503 비즈니스 예외로 변환한다")
    void checkAttempt_convertsDataAccessFailureToServiceUnavailable() {
        stubIdentifiers();

        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException(
                        "database unavailable"
                );

        when(transactionService.consumeIpToken(IP_HASH))
                .thenThrow(failure);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> loginRateLimiter.checkAttempt(
                        CLIENT_IP,
                        EMAIL
                )
        );

        assertEquals(
                ErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE,
                exception.getErrorCode()
        );
    }

    @Test
    @DisplayName("성공 초기화 중 저장소 장애도 503 비즈니스 예외로 변환한다")
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
    @DisplayName("프로그래밍 오류는 저장소 장애로 변환하지 않는다")
    void checkAttempt_doesNotConvertProgrammingError() {
        stubIdentifiers();

        IllegalStateException failure =
                new IllegalStateException(
                        "unexpected state"
                );

        when(transactionService.consumeIpToken(IP_HASH))
                .thenThrow(failure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> loginRateLimiter.checkAttempt(
                        CLIENT_IP,
                        EMAIL
                )
        );

        assertSame(failure, thrown);
    }

    private void stubIdentifiers() {
        when(identifierHasher.hashIp(CLIENT_IP))
                .thenReturn(IP_HASH);

        when(identifierHasher.hashEmail(EMAIL))
                .thenReturn(EMAIL_HASH);
    }
}
