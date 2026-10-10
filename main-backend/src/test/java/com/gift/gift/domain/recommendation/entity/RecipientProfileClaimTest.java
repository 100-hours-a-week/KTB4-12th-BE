package com.gift.gift.domain.recommendation.entity;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.*;

class RecipientProfileClaimTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 3, 0);
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    @Test
    void noClaimHasNoOwner() {
        RecipientProfile profile = profile();
        assertThat(profile.hasActiveDispatchClaim(NOW, TIMEOUT)).isFalse();
        assertThat(profile.matchesDispatchClaim(null)).isFalse();
        assertThat(profile.releaseDispatchClaim(null)).isFalse();
    }

    @Test
    void claimExpiresExactlyAtBoundary() {
        RecipientProfile profile = profile();
        profile.claimDispatch("first", NOW, NOW.minusHours(2));
        assertThat(profile.hasActiveDispatchClaim(NOW.plus(TIMEOUT).minusNanos(1), TIMEOUT)).isTrue();
        assertThat(profile.hasActiveDispatchClaim(NOW.plus(TIMEOUT), TIMEOUT)).isFalse();
    }

    @Test
    void wrongTokenCannotReleaseClaim() {
        RecipientProfile profile = profile();
        LocalDateTime snapshot = NOW.minusHours(2);
        profile.claimDispatch("first", NOW, snapshot);
        assertThat(profile.releaseDispatchClaim("other")).isFalse();
        assertThat(profile.getDispatchClaimToken()).isEqualTo("first");
        assertThat(profile.getDispatchClaimedAt()).isEqualTo(NOW);
        assertThat(profile.getDispatchClaimLastChangedAt()).isEqualTo(snapshot);
    }

    @Test
    void ownerReleasesAllThreeFields() {
        RecipientProfile profile = profile();
        profile.claimDispatch("first", NOW, NOW.minusHours(2));
        assertThat(profile.releaseDispatchClaim("first")).isTrue();
        assertThat(profile.getDispatchClaimToken()).isNull();
        assertThat(profile.getDispatchClaimedAt()).isNull();
        assertThat(profile.getDispatchClaimLastChangedAt()).isNull();
    }

    @Test
    void expiredClaimCannotBeOverwrittenWithoutRelease() {
        RecipientProfile profile = profile();
        profile.claimDispatch("first", NOW, null);
        assertThatThrownBy(() -> profile.claimDispatch("second", NOW.plus(TIMEOUT), null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(profile.getDispatchClaimToken()).isEqualTo("first");
    }

    @Test
    void newChangePreservesExistingClaimSnapshot() {
        RecipientProfile profile = profile();
        LocalDateTime snapshot = NOW.minusHours(2);
        profile.recordPreferenceChange(snapshot);
        profile.claimDispatch("first", NOW, snapshot);
        profile.recordPreferenceChange(NOW);
        assertThat(profile.getLastChangedAt()).isEqualTo(NOW);
        assertThat(profile.getDispatchClaimToken()).isEqualTo("first");
        assertThat(profile.getDispatchClaimLastChangedAt()).isEqualTo(snapshot);
    }

    @Test
    void recoveryClaimAllowsNullChangeSnapshot() {
        RecipientProfile profile = profile();
        profile.claimDispatch("recovery", NOW, null);
        assertThat(profile.hasActiveDispatchClaim(NOW, TIMEOUT)).isTrue();
        assertThat(profile.getDispatchClaimLastChangedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveTimeout(long seconds) {
        assertThatThrownBy(() -> profile().hasActiveDispatchClaim(NOW, Duration.ofSeconds(seconds)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RecipientProfile profile() {
        return new RecipientProfile(new User("claim@example.com", "$2a$10$" + "a".repeat(53),
                "수신자", LocalDate.of(2000, 1, 1)));
    }
}
