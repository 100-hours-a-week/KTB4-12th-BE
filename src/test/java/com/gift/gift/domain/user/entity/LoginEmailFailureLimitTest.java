package com.gift.gift.domain.user.entity;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.auth.entity.LoginEmailFailureLimit;
import com.gift.gift.domain.auth.support.LoginRateLimitDecision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginEmailFailureLimitTest {

    private static final String IDENTIFIER_HASH =
            "b".repeat(64);

    private static final LocalDateTime BASE_TIME =
            LocalDateTime.of(2026, 9, 20, 12, 0);

    @Test
    @DisplayName("첫 번째부터 다섯 번째 로그인 실패까지 현재 요청을 처리한다")
    void recordFailure_allowsFirstFiveFailures() {
        LoginEmailFailureLimit failureLimit =
                newFailureLimit();

        for (int failure = 1; failure <= 5; failure++) {
            LoginRateLimitDecision decision =
                    failureLimit.recordFailure(BASE_TIME);

            assertTrue(decision.permitted());
            assertEquals(0, decision.retryAfterSeconds());
        }

        assertEquals(1, failureLimit.getBackoffLevel());
        assertEquals(
                BASE_TIME.plusSeconds(30),
                failureLimit.getBlockedUntil()
        );
    }

    @Test
    @DisplayName("다섯 번째 실패 이후 요청은 30초 동안 제한한다")
    void inspect_rejectsRequestAfterFifthFailure() {
        LoginEmailFailureLimit failureLimit =
                newFailureLimit();

        recordFiveFailures(
                failureLimit,
                BASE_TIME
        );

        LoginRateLimitDecision decision =
                failureLimit.inspect(
                        BASE_TIME.plusSeconds(1)
                );

        assertFalse(decision.permitted());
        assertEquals(29, decision.retryAfterSeconds());
    }

    @Test
    @DisplayName("차단 중에는 남아 있는 대기 시간을 초 단위로 반환한다")
    void inspect_returnsRemainingBackoffSeconds() {
        LoginEmailFailureLimit failureLimit =
                newFailureLimit();

        recordFiveFailures(
                failureLimit,
                BASE_TIME
        );

        LoginRateLimitDecision decision =
                failureLimit.inspect(
                        BASE_TIME.plusSeconds(17)
                );

        assertFalse(decision.permitted());
        assertEquals(13, decision.retryAfterSeconds());
    }

    @Test
    @DisplayName("차단 종료 시각부터 다시 실패를 집계할 수 있다")
    void recordFailure_restartsFailureCountAfterBackoffExpires() {
        LoginEmailFailureLimit failureLimit =
                newFailureLimit();

        recordFiveFailures(
                failureLimit,
                BASE_TIME
        );

        LocalDateTime afterBackoff =
                BASE_TIME.plusSeconds(30);

        assertTrue(
                failureLimit.inspect(afterBackoff).permitted()
        );

        LoginRateLimitDecision decision =
                failureLimit.recordFailure(afterBackoff);

        assertTrue(decision.permitted());
        assertEquals(1, failureLimit.getFailureCount());
        assertNull(failureLimit.getBlockedUntil());
        assertEquals(
                afterBackoff,
                failureLimit.getWindowStartedAt()
        );
    }

    @Test
    @DisplayName("반복 실패 시 Backoff가 30초부터 최대 900초까지 증가한다")
    void recordFailure_increasesBackoffByStage() {
        LoginEmailFailureLimit failureLimit =
                newFailureLimit();

        long[] expectedSeconds = {
                30,
                60,
                120,
                240,
                480,
                900
        };

        LocalDateTime currentTime = BASE_TIME;

        for (int index = 0;
             index < expectedSeconds.length;
             index++) {
            recordFiveFailures(
                    failureLimit,
                    currentTime
            );

            assertEquals(
                    index + 1,
                    failureLimit.getBackoffLevel()
            );
            assertEquals(
                    currentTime.plusSeconds(
                            expectedSeconds[index]
                    ),
                    failureLimit.getBlockedUntil()
            );

            currentTime = failureLimit.getBlockedUntil();

            assertTrue(
                    failureLimit
                            .inspect(currentTime)
                            .permitted()
            );
        }
    }

    @Test
    @DisplayName("최대 Backoff 단계 이후에도 대기 시간은 900초를 초과하지 않는다")
    void recordFailure_capsBackoffAtNineHundredSeconds() {
        LoginEmailFailureLimit failureLimit =
                newFailureLimit();

        long[] backoffSeconds = {
                30,
                60,
                120,
                240,
                480,
                900,
                900
        };

        LocalDateTime currentTime = BASE_TIME;

        for (long expectedSeconds : backoffSeconds) {
            recordFiveFailures(
                    failureLimit,
                    currentTime
            );

            assertEquals(
                    currentTime.plusSeconds(expectedSeconds),
                    failureLimit.getBlockedUntil()
            );

            currentTime = failureLimit.getBlockedUntil();
            failureLimit.inspect(currentTime);
        }

        assertEquals(6, failureLimit.getBackoffLevel());
    }

    @Test
    @DisplayName("5분 동안 다섯 번에 도달하지 않으면 실패 횟수를 새 구간에서 다시 집계한다")
    void recordFailure_resetsCountAfterFiveMinuteWindow() {
        LoginEmailFailureLimit failureLimit =
                newFailureLimit();

        for (int failure = 0; failure < 4; failure++) {
            failureLimit.recordFailure(BASE_TIME);
        }

        LocalDateTime nextWindow =
                BASE_TIME.plusMinutes(5);

        LoginRateLimitDecision decision =
                failureLimit.recordFailure(nextWindow);

        assertTrue(decision.permitted());
        assertEquals(1, failureLimit.getFailureCount());
        assertEquals(
                nextWindow,
                failureLimit.getWindowStartedAt()
        );
        assertEquals(0, failureLimit.getBackoffLevel());
        assertNull(failureLimit.getBlockedUntil());
    }

    @Test
    @DisplayName("차단 시간이 만료되어도 Backoff 단계는 로그인 성공 전까지 유지한다")
    void inspect_preservesBackoffLevelAfterBlockExpires() {
        LoginEmailFailureLimit failureLimit =
                newFailureLimit();

        recordFiveFailures(
                failureLimit,
                BASE_TIME
        );

        LoginRateLimitDecision decision =
                failureLimit.inspect(
                        BASE_TIME.plusSeconds(30)
                );

        assertTrue(decision.permitted());
        assertEquals(1, failureLimit.getBackoffLevel());
        assertEquals(0, failureLimit.getFailureCount());
        assertNull(failureLimit.getBlockedUntil());
    }

    private LoginEmailFailureLimit newFailureLimit() {
        return new LoginEmailFailureLimit(
                IDENTIFIER_HASH,
                BASE_TIME
        );
    }

    private void recordFiveFailures(
            LoginEmailFailureLimit failureLimit,
            LocalDateTime now
    ) {
        for (int failure = 0; failure < 5; failure++) {
            LoginRateLimitDecision decision =
                    failureLimit.recordFailure(now);

            assertTrue(decision.permitted());
        }
    }
}
