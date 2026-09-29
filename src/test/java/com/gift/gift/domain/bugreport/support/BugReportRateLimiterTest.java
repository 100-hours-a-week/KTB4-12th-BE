package com.gift.gift.domain.bugreport.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.bugreport.exception.BugReportException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BugReportRateLimiterTest {

    private static final Long USER_ID = 1L;
    private static final String IP = "127.0.0.1";

    private final MutableClock clock = new MutableClock(
            Instant.parse("2026-01-01T00:00:00Z")
    );
    private final BugReportRateLimiter rateLimiter =
            new BugReportRateLimiter(clock);

    @Test
    @DisplayName("사용자당 분당 5회까지는 허용하고 6번째 요청은 거부한다")
    void checkUser_rejectsSixthAttempt_withinOneMinute() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.checkUser(USER_ID);
        }

        assertThatThrownBy(() -> rateLimiter.checkUser(USER_ID))
                .isInstanceOf(BugReportException.class);
    }

    @Test
    @DisplayName("IP당 분당 20회까지는 허용하고 21번째 요청은 거부한다")
    void checkIp_rejects21stAttempt_withinOneMinute() {
        for (int i = 0; i < 20; i++) {
            rateLimiter.checkIp(IP);
        }

        assertThatThrownBy(() -> rateLimiter.checkIp(IP))
                .isInstanceOf(BugReportException.class);
    }

    @Test
    @DisplayName("1분이 지나면 오래된 요청 기록이 만료되어 다시 허용된다")
    void checkUser_allowsAgain_afterWindowExpires() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.checkUser(USER_ID);
        }

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));

        assertThatCode(() -> rateLimiter.checkUser(USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("서로 다른 사용자의 요청 횟수는 독립적으로 집계된다")
    void checkUser_countsIndependently_perUser() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.checkUser(USER_ID);
        }

        assertThatCode(() -> rateLimiter.checkUser(2L))
                .doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
