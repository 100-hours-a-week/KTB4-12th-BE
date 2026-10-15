package com.gift.gift.domain.gift.support;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GiftPolicyTest {

    @Test
    @DisplayName("선물은 발송 다음 날부터 7일이 지난 자정에 만료된다")
    void expiresAt_usesCalendarDate() {
        LocalDateTime sentAt =
                LocalDateTime.of(2026, 10, 15, 14, 30);

        LocalDateTime expiresAt = GiftPolicy.expiresAt(sentAt);

        assertThat(expiresAt).isEqualTo(
                LocalDateTime.of(2026, 10, 23, 0, 0)
        );
    }

    @Test
    @DisplayName("발송 시각이 달라도 같은 날짜의 선물은 같은 시각에 만료된다")
    void expiresAt_ignoresTimeOfDay() {
        LocalDateTime morning =
                LocalDateTime.of(2026, 10, 15, 0, 1);

        LocalDateTime night =
                LocalDateTime.of(2026, 10, 15, 23, 59);

        assertThat(GiftPolicy.expiresAt(morning))
                .isEqualTo(GiftPolicy.expiresAt(night));
    }

    @Test
    @DisplayName("발송 시각이 없으면 만료 시각을 계산할 수 없다")
    void expiresAt_rejectsNullSentAt() {
        assertThatThrownBy(
                () -> GiftPolicy.expiresAt(null)
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
