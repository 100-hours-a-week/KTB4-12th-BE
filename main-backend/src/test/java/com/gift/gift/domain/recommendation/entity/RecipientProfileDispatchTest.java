package com.gift.gift.domain.recommendation.entity;

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

    @Test
    @DisplayName("202 응답은 PENDING 상태와 Pending 시각을 저장한다")
    void applyAcceptedResponse_marksPending() {
        RecipientProfile profile = profile();
        LocalDateTime changedAt = NOW.minusHours(2);
        profile.recordPreferenceChange(changedAt);
        long sourceVersion = profile.createNextSourceVersion();

        profile.applyAcceptedResponse(sourceVersion, changedAt, NOW);

        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.PENDING);
        assertThat(profile.getPendingSince()).isEqualTo(NOW);
        assertThat(profile.getLastChangedAt()).isNull();
        assertThat(profile.getWindowStartedAt()).isNull();
    }

    @Test
    @DisplayName("요청 중 추가 변경이 발생하면 변경 시각을 유지한다")
    void applyAcceptedResponse_keepsChangeWindowAfterNewChange() {
        RecipientProfile profile = profile();
        LocalDateTime snapshottedAt = NOW.minusHours(2);
        LocalDateTime changedDuringRequest = NOW.minusMinutes(5);
        profile.recordPreferenceChange(snapshottedAt);
        long sourceVersion = profile.createNextSourceVersion();
        profile.recordPreferenceChange(changedDuringRequest);

        profile.applyAcceptedResponse(
                sourceVersion,
                snapshottedAt,
                NOW
        );

        assertThat(profile.getLastChangedAt())
                .isEqualTo(changedDuringRequest);
        assertThat(profile.getWindowStartedAt())
                .isEqualTo(snapshottedAt);
    }

    @Test
    @DisplayName("같은 버전이 이미 완료됐다면 202가 COMPLETED를 덮어쓰지 않는다")
    void applyAcceptedResponse_doesNotOverwriteCompletedProfile() {
        RecipientProfile profile = profile();
        LocalDateTime changedAt = NOW.minusHours(2);
        profile.recordPreferenceChange(changedAt);
        long sourceVersion = profile.createNextSourceVersion();
        profile.markPending(NOW.minusMinutes(1));
        profile.markCompleted(sourceVersion);

        profile.applyAcceptedResponse(sourceVersion, changedAt, NOW);

        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.COMPLETED);
        assertThat(profile.getPendingSince()).isNull();
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
