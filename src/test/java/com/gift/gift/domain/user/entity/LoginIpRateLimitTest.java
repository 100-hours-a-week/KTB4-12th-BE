package com.gift.gift.domain.user.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.auth.entity.LoginIpRateLimit;
import com.gift.gift.domain.auth.support.LoginRateLimitDecision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginIpRateLimitTest {

    private static final String IDENTIFIER_HASH =
            "a".repeat(64);

    private static final LocalDateTime BASE_TIME =
            LocalDateTime.of(2026, 9, 20, 12, 0);

    @Test
    @DisplayName("초기 Token Bucket은 연속 10회 요청을 허용한다")
    void consume_allowsFirstTenRequests() {
        LoginIpRateLimit rateLimit =
                new LoginIpRateLimit(
                        IDENTIFIER_HASH,
                        BASE_TIME
                );

        for (int request = 0; request < 10; request++) {
            LoginRateLimitDecision decision =
                    rateLimit.consume(BASE_TIME);

            assertTrue(decision.permitted());
            assertEquals(0, decision.retryAfterSeconds());
        }

        assertEquals(
                BigDecimal.ZERO,
                rateLimit.getAvailableTokens()
        );
    }

    @Test
    @DisplayName("토큰을 모두 사용한 직후 요청은 6초 동안 제한한다")
    void consume_rejectsEleventhImmediateRequest() {
        LoginIpRateLimit rateLimit =
                emptyRateLimit();

        LoginRateLimitDecision decision =
                rateLimit.consume(BASE_TIME);

        assertFalse(decision.permitted());
        assertEquals(6, decision.retryAfterSeconds());
        assertEquals(
                BigDecimal.ZERO,
                rateLimit.getAvailableTokens()
        );
    }

    @Test
    @DisplayName("토큰을 모두 사용하고 3초가 지나면 남은 3초를 반환한다")
    void consume_returnsRemainingThreeSeconds() {
        LoginIpRateLimit rateLimit =
                emptyRateLimit();

        LoginRateLimitDecision decision =
                rateLimit.consume(
                        BASE_TIME.plusSeconds(3)
                );

        assertFalse(decision.permitted());
        assertEquals(3, decision.retryAfterSeconds());
    }

    @Test
    @DisplayName("토큰을 모두 사용하고 6초가 지나면 한 번의 요청을 허용한다")
    void consume_refillsOneTokenAfterSixSeconds() {
        LoginIpRateLimit rateLimit =
                emptyRateLimit();

        LoginRateLimitDecision decision =
                rateLimit.consume(
                        BASE_TIME.plusSeconds(6)
                );

        assertTrue(decision.permitted());
        assertEquals(0, decision.retryAfterSeconds());
        assertEquals(
                BigDecimal.ZERO,
                rateLimit.getAvailableTokens()
        );
    }

    @Test
    @DisplayName("장시간이 지나도 Token Bucket 용량은 10을 초과하지 않는다")
    void consume_doesNotRefillBeyondCapacity() {
        LoginIpRateLimit rateLimit =
                new LoginIpRateLimit(
                        IDENTIFIER_HASH,
                        BASE_TIME
                );

        rateLimit.consume(BASE_TIME);

        LoginRateLimitDecision decision =
                rateLimit.consume(
                        BASE_TIME.plusHours(1)
                );

        assertTrue(decision.permitted());
        assertEquals(
                BigDecimal.valueOf(9),
                rateLimit.getAvailableTokens()
        );
    }

    @Test
    @DisplayName("현재 시각이 마지막 보충 시각보다 이전이어도 음수 대기 시간을 반환하지 않는다")
    void consume_doesNotReturnNegativeRetryAfterWhenClockMovesBackward() {
        LoginIpRateLimit rateLimit =
                emptyRateLimit();

        LoginRateLimitDecision decision =
                rateLimit.consume(
                        BASE_TIME.minusSeconds(10)
                );

        assertFalse(decision.permitted());
        assertEquals(6, decision.retryAfterSeconds());
        assertTrue(decision.retryAfterSeconds() > 0);
    }

    private LoginIpRateLimit emptyRateLimit() {
        LoginIpRateLimit rateLimit =
                new LoginIpRateLimit(
                        IDENTIFIER_HASH,
                        BASE_TIME
                );

        for (int request = 0; request < 10; request++) {
            rateLimit.consume(BASE_TIME);
        }

        return rateLimit;
    }
}
