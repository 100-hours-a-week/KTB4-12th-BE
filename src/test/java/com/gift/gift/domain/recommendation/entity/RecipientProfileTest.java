package com.gift.gift.domain.recommendation.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.*;

class RecipientProfileTest {

    private RecipientProfile profile;

    @BeforeEach
    void setUp() {
        User recipient = new User(
                "recipient@example.com",
                "$2a$10$" + "a".repeat(53),
                "수신자",
                LocalDate.of(2000, 1, 1)
        );

        profile = new RecipientProfile(recipient);
    }

    @Test
    @DisplayName("프로파일은 NONE과 버전 0 및 재시도 횟수 0으로 생성된다")
    void constructor_initializesDefaultState() {
        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.NONE);
        assertThat(profile.getSourceVersion()).isZero();
        assertThat(profile.getAnalyzedSourceVersion()).isZero();
        assertThat(profile.getRetryCount()).isZero();
        assertThat(profile.getLastChangedAt()).isNull();
        assertThat(profile.getWindowStartedAt()).isNull();
        assertThat(profile.getPendingSince()).isNull();
    }

    @Test
    @DisplayName("최초 비선호 변경은 마지막 변경 시각과 변경 구간 시작 시각을 기록한다")
    void recordPreferenceChange_recordsBothTimesInitially() {
        LocalDateTime changedAt =
                LocalDateTime.of(2026, 9, 24, 10, 0);

        profile.recordPreferenceChange(changedAt);

        assertThat(profile.getLastChangedAt())
                .isEqualTo(changedAt);
        assertThat(profile.getWindowStartedAt())
                .isEqualTo(changedAt);
    }

    @Test
    @DisplayName("추가 비선호 변경은 마지막 변경 시각만 갱신한다")
    void recordPreferenceChange_preservesWindowStart() {
        LocalDateTime first =
                LocalDateTime.of(2026, 9, 24, 10, 0);
        LocalDateTime second =
                LocalDateTime.of(2026, 9, 24, 10, 30);

        profile.recordPreferenceChange(first);
        profile.recordPreferenceChange(second);

        assertThat(profile.getLastChangedAt())
                .isEqualTo(second);
        assertThat(profile.getWindowStartedAt())
                .isEqualTo(first);
    }

    @Test
    @DisplayName("새 AI 요청은 sourceVersion을 증가시키고 재시도 횟수를 초기화한다")
    void createNextSourceVersion_incrementsVersionAndResetsRetry() {
        profile.createNextSourceVersion();
        profile.increaseRetryCount();

        long nextVersion = profile.createNextSourceVersion();

        assertThat(nextVersion).isEqualTo(2);
        assertThat(profile.getSourceVersion()).isEqualTo(2);
        assertThat(profile.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("재시도는 sourceVersion을 증가시키지 않는다")
    void retry_doesNotIncreaseSourceVersion() {
        profile.createNextSourceVersion();

        profile.increaseRetryCount();
        profile.increaseRetryCount();

        assertThat(profile.getSourceVersion()).isEqualTo(1);
        assertThat(profile.getRetryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("새 요청은 NONE에서 PENDING으로 전이한다")
    void markPending_changesStatusToPending() {
        profile.createNextSourceVersion();

        LocalDateTime pendingAt =
                LocalDateTime.of(2026, 9, 24, 11, 30);

        profile.markPending(pendingAt);

        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.PENDING);
        assertThat(profile.getPendingSince())
                .isEqualTo(pendingAt);
    }

    @Test
    @DisplayName("PENDING 프로파일은 COMPLETED로 전이한다")
    void markCompleted_changesStatusAndAnalyzedVersion() {
        profile.createNextSourceVersion();
        profile.markPending(
                LocalDateTime.of(2026, 9, 24, 11, 30)
        );
        profile.increaseRetryCount();

        profile.markCompleted(1);

        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.COMPLETED);
        assertThat(profile.getAnalyzedSourceVersion())
                .isEqualTo(1);
        assertThat(profile.getPendingSince()).isNull();
        assertThat(profile.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("PENDING 프로파일은 FAILED로 전이한다")
    void markFailed_changesStatusWithoutUpdatingAnalyzedVersion() {
        profile.createNextSourceVersion();
        profile.markPending(
                LocalDateTime.of(2026, 9, 24, 11, 30)
        );

        profile.markFailed();

        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.FAILED);
        assertThat(profile.getSourceVersion()).isEqualTo(1);
        assertThat(profile.getAnalyzedSourceVersion()).isZero();
        assertThat(profile.getPendingSince()).isNull();
    }

    @Test
    @DisplayName("재시도 횟수는 최대 2회까지만 증가한다")
    void increaseRetryCount_rejectsMoreThanTwoRetries() {
        assertThat(profile.increaseRetryCount()).isEqualTo(1);
        assertThat(profile.increaseRetryCount()).isEqualTo(2);

        assertThatIllegalStateException()
                .isThrownBy(profile::increaseRetryCount);

        assertThat(profile.getRetryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("PENDING 상태에서는 새 요청 버전을 생성할 수 없다")
    void createNextSourceVersion_rejectsPendingProfile() {
        profile.createNextSourceVersion();
        profile.markPending(LocalDateTime.now());

        assertThatIllegalStateException()
                .isThrownBy(profile::createNextSourceVersion);
    }

    @Test
    @DisplayName("현재 요청 버전과 다른 버전은 완료 처리할 수 없다")
    void markCompleted_rejectsDifferentVersion() {
        profile.createNextSourceVersion();
        profile.markPending(LocalDateTime.now());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> profile.markCompleted(2));
    }
}
