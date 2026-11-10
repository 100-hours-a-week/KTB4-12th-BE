package com.gift.gift.domain.recommendation.profiling.entity;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;

class RecipientProfileDispatchTest {

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 9, 27, 12, 0);
    private static final Duration QUIET_PERIOD = Duration.ofHours(1);
    private static final Duration MAXIMUM_WINDOW = Duration.ofHours(6);

    @Test
    @DisplayName("마지막 변경 후 1시간이 지나면 전송 대상이다")
    void isDispatchDue_returnsTrueAfterQuietPeriod() {
        RecipientProfile profile = profile();
        profile.recordPreferenceChange(NOW.minusHours(1));

        assertThat(profile.isDispatchDue(
                NOW,
                QUIET_PERIOD,
                MAXIMUM_WINDOW
        )).isTrue();
    }

    @Test
    @DisplayName("최초 변경 후 6시간이 지나면 최근 변경이 있어도 전송 대상이다")
    void isDispatchDue_returnsTrueAfterMaximumWindow() {
        RecipientProfile profile = profile();
        profile.recordPreferenceChange(NOW.minusHours(6));
        profile.recordPreferenceChange(NOW.minusMinutes(10));

        assertThat(profile.isDispatchDue(
                NOW,
                QUIET_PERIOD,
                MAXIMUM_WINDOW
        )).isTrue();
    }

    @Test
    @DisplayName("1시간 디바운스와 6시간 최대 대기를 모두 충족하지 않으면 제외한다")
    void isDispatchDue_returnsFalseBeforeBothThresholds() {
        RecipientProfile profile = profile();
        profile.recordPreferenceChange(NOW.minusHours(2));
        profile.recordPreferenceChange(NOW.minusMinutes(30));

        assertThat(profile.isDispatchDue(
                NOW,
                QUIET_PERIOD,
                MAXIMUM_WINDOW
        )).isFalse();
    }

    private RecipientProfile profile() {
        return new RecipientProfile(new User(
                "recipient@example.com",
                "$2a$10$" + "a".repeat(53),
                "수신자",
                java.time.LocalDate.of(1990, 1, 1)
        ));
    }
}
