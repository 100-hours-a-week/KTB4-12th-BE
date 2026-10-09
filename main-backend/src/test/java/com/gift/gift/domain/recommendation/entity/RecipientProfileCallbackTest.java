package com.gift.gift.domain.recommendation.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.gift.gift.domain.recommendation.exception.RecommendationException;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipientProfileCallbackTest {

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 10, 5, 12, 0);

    private RecipientProfile profile;

    @BeforeEach
    void setUp() {
        profile = new RecipientProfile(new User(
                "recipient@example.com",
                "$2a$10$" + "a".repeat(53),
                "수신자",
                LocalDate.of(2000, 1, 1)
        ));
    }

    @Test
    @DisplayName("현재 요청 버전은 새 결과 저장 대상으로 판단한다")
    void shouldApplyCallback_acceptsCurrentVersion() {
        profile.createNextSourceVersion();

        assertThat(profile.shouldApplyCallback(1)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 2})
    @DisplayName("음수·최초 0·미래 버전은 INVALID_REQUEST로 거부한다")
    void shouldApplyCallback_rejectsInvalidVersion(long version) {
        profile.createNextSourceVersion();

        assertError(version, ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("초기 버전 0을 이미 저장된 결과로 판단하지 않는다")
    void shouldApplyCallback_rejectsInitialZero() {
        assertError(0, ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("이미 저장된 버전은 다시 저장하지 않는다")
    void shouldApplyCallback_returnsFalseForStoredVersion() {
        profile.createNextSourceVersion();
        profile.markCompleted(1);

        assertThat(profile.shouldApplyCallback(1)).isFalse();
    }

    @Test
    @DisplayName("저장된 결과보다 오래된 버전은 STALE_SOURCE_VERSION으로 거부한다")
    void shouldApplyCallback_rejectsStaleVersion() {
        profile.createNextSourceVersion();
        profile.markCompleted(1);
        profile.createNextSourceVersion();
        profile.markCompleted(2);

        assertError(1, ErrorCode.STALE_SOURCE_VERSION);
    }

    @Test
    @DisplayName("최신 결과 저장은 Pending 시각과 재시도 횟수를 초기화한다")
    void markCompleted_resetsPendingAndRetryCount() {
        profile.createNextSourceVersion();
        profile.markPending(NOW);
        profile.increaseRetryCount();

        profile.markCompleted(1);

        assertThat(profile.getAnalyzedSourceVersion()).isEqualTo(1);
        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.COMPLETED);
        assertThat(profile.getPendingSince()).isNull();
        assertThat(profile.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("202 반영 전 빠른 콜백도 완료할 수 있다")
    void markCompleted_acceptsCallbackBeforeAcceptedResponse() {
        LocalDateTime changedAt = NOW.minusHours(2);
        profile.recordPreferenceChange(changedAt);
        profile.createNextSourceVersion();

        profile.markCompleted(1);
        profile.applyAcceptedResponse(1, changedAt, NOW);

        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.COMPLETED);
        assertThat(profile.getPendingSince()).isNull();
    }

    @Test
    @DisplayName("FAILED 상태에서도 최신 버전의 늦은 결과를 완료할 수 있다")
    void markCompleted_acceptsLateCallbackAfterFailure() {
        profile.createNextSourceVersion();
        profile.markPending(NOW);
        profile.markFailed();

        profile.markCompleted(1);

        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.COMPLETED);
        assertThat(profile.getAnalyzedSourceVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("이전 버전 재전송은 최신 요청의 PENDING 상태를 변경하지 않는다")
    void markCompleted_keepsNewPendingOnDuplicateCallback() {
        profile.createNextSourceVersion();
        profile.markCompleted(1);
        profile.createNextSourceVersion();
        profile.markPending(NOW);
        profile.increaseRetryCount();

        profile.markCompleted(1);

        assertThat(profile.getAnalyzedSourceVersion()).isEqualTo(1);
        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.PENDING);
        assertThat(profile.getPendingSince()).isEqualTo(NOW);
        assertThat(profile.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("중간 버전 결과는 반영하되 최신 요청의 대기 정보를 유지한다")
    void markCompleted_keepsPendingForIntermediateVersion() {
        profile.createNextSourceVersion();
        profile.createNextSourceVersion();
        profile.markPending(NOW);
        profile.increaseRetryCount();

        profile.markCompleted(1);

        assertThat(profile.getAnalyzedSourceVersion()).isEqualTo(1);
        assertThat(profile.getSourceVersion()).isEqualTo(2);
        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.PENDING);
        assertThat(profile.getPendingSince()).isEqualTo(NOW);
        assertThat(profile.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("콜백 완료는 요청 도중 발생한 비선호 변경을 제거하지 않는다")
    void markCompleted_preservesPreferenceChangeWindow() {
        profile.createNextSourceVersion();
        profile.markPending(NOW);
        profile.recordPreferenceChange(NOW.plusMinutes(1));

        profile.markCompleted(1);

        assertThat(profile.getLastChangedAt())
                .isEqualTo(NOW.plusMinutes(1));
        assertThat(profile.getWindowStartedAt())
                .isEqualTo(NOW.plusMinutes(1));
    }

    private void assertError(long version, ErrorCode expectedError) {
        assertThatThrownBy(() -> profile.shouldApplyCallback(version))
                .isInstanceOfSatisfying(
                        RecommendationException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(expectedError)
                );
    }
}
