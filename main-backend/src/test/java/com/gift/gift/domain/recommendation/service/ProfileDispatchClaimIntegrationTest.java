package com.gift.gift.domain.recommendation.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.recommendation.dto.request.AiProfileRequest;
import com.gift.gift.domain.recommendation.dto.response.AiProfileAcceptedResponse;
import com.gift.gift.domain.recommendation.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.entity.RecipientProfileStatus;
import com.gift.gift.domain.recommendation.repository.RecipientProfileRepository;
import com.gift.gift.domain.recommendation.support.PreparedProfileDispatch;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.infrastructure.ai.AiProfilingClient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "app.scheduling.enabled=false",
        "app.ai-profile.scheduling-enabled=false",
        "app.ai-profile.dispatch-claim-timeout=2m"
})
class ProfileDispatchClaimIntegrationTest {
    // 다른 테스트가 남긴 최근 일반 후보와 시간을 분리한다.
    private static final LocalDateTime NOW = LocalDateTime.of(2000, 1, 1, 12, 0);

    @Autowired private RecipientProfileRepository profiles;
    @Autowired private UserRepository users;
    @Autowired private ProfileDispatchTransactionService transactions;
    @Autowired private ProfileDispatchService dispatch;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private Clock clock;
    @MockitoBean private AiProfilingClient client;

    private TransactionTemplate tx;
    private Long userId;
    private Long profileId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        when(clock.instant()).thenReturn(NOW.toInstant(ZoneOffset.UTC));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        tx.executeWithoutResult(status -> {
            User user = users.saveAndFlush(new User(
                    "claim-" + UUID.randomUUID() + "@example.com",
                    "$2a$10$" + "a".repeat(53), "선점수신자", LocalDate.of(1990, 1, 1)));
            userId = user.getId();
            profileId = profiles.saveAndFlush(new RecipientProfile(user)).getId();
        });
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(status -> {
            if (profileId != null) {
                profiles.deleteById(profileId);
                profiles.flush();
            }
            if (userId != null) {
                users.deleteById(userId);
                users.flush();
            }
        });
    }

    @Test
    void concurrentNormalPreparationHasOneOwner() throws Exception {
        changeDue();
        var results = race(() -> transactions.prepareDispatch(profileId),
                () -> transactions.prepareDispatch(profileId));
        assertThat(results.stream().filter(Optional::isPresent).count()).isEqualTo(1);
        assertThat(profile().getSourceVersion()).isEqualTo(1);
        assertThat(profile().getDispatchClaimToken()).isNotNull();
    }

    @Test
    void concurrentDebounceRestartDoesNotIncreaseCountsTwice() throws Exception {
        changeDue();
        var first = transactions.prepareDispatch(profileId).orElseThrow();
        transactions.applyFailedDispatch(first);
        assertThat(profile().getRetryCount()).isEqualTo(1);
        when(clock.instant()).thenReturn(NOW.plusHours(1).toInstant(ZoneOffset.UTC));
        var results = race(() -> transactions.prepareDispatch(profileId),
                () -> transactions.prepareDispatch(profileId));
        assertThat(results.stream().filter(Optional::isPresent).count()).isEqualTo(1);
        assertThat(profile().getSourceVersion()).isEqualTo(2);
        assertThat(profile().getRetryCount()).isEqualTo(1);
    }

    @Test
    void concurrentRecoveryPreparationHasOneOwner() throws Exception {
        pendingDue();
        var results = race(() -> transactions.prepareRecovery(profileId),
                () -> transactions.prepareRecovery(profileId));
        assertThat(results.stream().filter(Optional::isPresent).count()).isEqualTo(1);
        assertThat(profile().getSourceVersion()).isEqualTo(1);
        assertThat(profile().getRetryCount()).isZero();
        assertThat(profile().getPendingSince()).isEqualTo(NOW.minusHours(7));
    }

    @Test
    void normalAndRecoveryRaceRechecksChangedState() throws Exception {
        pendingDue();
        changeDue();
        var results = race(() -> transactions.prepareDispatch(profileId).isPresent(),
                () -> transactions.prepareRecovery(profileId).isPresent());
        assertThat(results).containsExactly(true, false);
        assertThat(profile().getSourceVersion()).isEqualTo(2);
    }

    @Test
    void activeNormalClaimBlocksBothPathsAndCandidateQueries() {
        pendingDue();
        changeDue();
        transactions.prepareDispatch(profileId).orElseThrow();
        assertThat(transactions.prepareDispatch(profileId)).isEmpty();
        assertThat(transactions.prepareRecovery(profileId)).isEmpty();
        assertThat(normalCandidates()).doesNotContain(profileId);
        assertThat(recoveryCandidates()).doesNotContain(profileId);
    }

    @Test
    void activeRecoveryClaimBlocksBothPathsEvenAfterNewChange() {
        pendingDue();
        var recovery = transactions.prepareRecovery(profileId).orElseThrow();
        changeDue();
        assertThat(transactions.prepareDispatch(profileId)).isEmpty();
        assertThat(transactions.prepareRecovery(profileId)).isEmpty();
        assertThat(profile().getDispatchClaimToken()).isEqualTo(recovery.claimToken());
        assertThat(profile().getSourceVersion()).isEqualTo(1);
    }

    @Test
    void committedPreparationWithoutHttpCanBeReclaimedWithSameVersion() {
        changeDue();
        var first = transactions.prepareDispatch(profileId).orElseThrow();
        assertThat(profile().getDispatchClaimToken()).isEqualTo(first.claimToken());
        // 결과를 반영하지 않고 준비 커밋 후 실행자가 종료한 상태를 재현한다.
        expireClaim();
        assertThat(normalCandidates()).contains(profileId);
        var second = transactions.prepareDispatch(profileId).orElseThrow();
        assertThat(second.sourceVersion()).isEqualTo(first.sourceVersion());
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(profile().getRetryCount()).isZero();
    }

    @Test
    void expiredRecoveryUsesSameVersionAndOriginalPendingTime() {
        pendingDue();
        var first = transactions.prepareRecovery(profileId).orElseThrow();
        expireClaim();
        assertThat(recoveryCandidates()).contains(profileId);
        var second = transactions.prepareRecovery(profileId).orElseThrow();
        assertThat(second.sourceVersion()).isEqualTo(first.sourceVersion());
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(second.snapshottedPendingSince()).isEqualTo(first.snapshottedPendingSince());
        assertThat(profile().getRetryCount()).isZero();
    }

    @Test
    void oldNormalSuccessFailureAndReleaseCannotChangeNewClaim() {
        changeDue();
        var first = transactions.prepareDispatch(profileId).orElseThrow();
        expireClaim();
        var second = transactions.prepareDispatch(profileId).orElseThrow();
        RecipientProfile before = profile();
        transactions.applyAcceptedResponse(first, accepted(first));
        assertThat(transactions.applyFailedDispatch(first))
                .isEqualTo(ProfileDispatchTransactionService.FailedDispatchResult.SKIPPED);
        Boolean released = tx.execute(status -> profiles.findByIdForUpdate(profileId)
                .orElseThrow().releaseDispatchClaim(first.claimToken()));
        assertThat(released).isFalse();
        RecipientProfile after = profile();
        assertThat(after.getDispatchClaimToken()).isEqualTo(second.claimToken());
        assertThat(after).usingRecursiveComparison()
                .ignoringFields("recipient", "updatedAt").isEqualTo(before);
    }

    @Test
    void oldRecoveryResultCannotChangeNewClaim() {
        pendingDue();
        var first = transactions.prepareRecovery(profileId).orElseThrow();
        expireClaim();
        var second = transactions.prepareRecovery(profileId).orElseThrow();
        assertThat(transactions.applyRecoveryResult(first))
                .isEqualTo(ProfileDispatchTransactionService.RecoveryDispatchResult.SKIPPED);
        assertThat(profile().getDispatchClaimToken()).isEqualTo(second.claimToken());
        assertThat(profile().getPendingSince()).isEqualTo(first.snapshottedPendingSince());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callbackBeforeNormalHttpResultPreservesCompletion(boolean failure) {
        changeDue();
        var prepared = transactions.prepareDispatch(profileId).orElseThrow();
        update(profile -> profile.markCompleted(prepared.sourceVersion()));
        if (failure) transactions.applyFailedDispatch(prepared);
        else transactions.applyAcceptedResponse(prepared, accepted(prepared));
        assertThat(profile().getProfileStatus()).isEqualTo(RecipientProfileStatus.COMPLETED);
        assertThat(profile().getPendingSince()).isNull();
        assertThat(profile().getLastChangedAt()).isNull();
        assertThat(profile().getRetryCount()).isZero();
        assertThat(profile().getDispatchClaimToken()).isNull();
    }

    @Test
    void expiredCompletedRecoveryClaimIsFoundAndCleanedWithoutResend() {
        pendingDue();
        var prepared = transactions.prepareRecovery(profileId).orElseThrow();
        update(profile -> profile.markCompleted(prepared.sourceVersion()));
        expireClaim();
        assertThat(normalCandidates()).contains(profileId);
        assertThat(recoveryCandidates()).doesNotContain(profileId);
        assertThat(transactions.prepareDispatch(profileId)).isEmpty();
        assertThat(transactions.prepareRecovery(profileId)).isEmpty();
        assertThat(profile().getProfileStatus()).isEqualTo(RecipientProfileStatus.COMPLETED);
        assertThat(profile().getDispatchClaimToken()).isNull();
    }

    @Test
    void expiredCompletedNormalClaimClearsOnlyItsOwnChange() {
        changeDue();
        var prepared = transactions.prepareDispatch(profileId).orElseThrow();
        update(profile -> profile.markCompleted(prepared.sourceVersion()));
        expireClaim();
        assertThat(transactions.prepareDispatch(profileId)).isEmpty();
        assertThat(profile().getLastChangedAt()).isNull();
        assertThat(profile().getDispatchClaimToken()).isNull();
        assertThat(profile().getSourceVersion()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiredClaimPreservesNewChangeUntilDebounceIsDue(boolean completed) {
        changeDue();
        var first = transactions.prepareDispatch(profileId).orElseThrow();
        update(profile -> {
            if (completed) profile.markCompleted(first.sourceVersion());
            profile.recordPreferenceChange(NOW);
        });
        expireClaim();
        assertThat(normalCandidates()).contains(profileId);
        assertThat(transactions.prepareDispatch(profileId)).isEmpty();
        assertThat(profile().getDispatchClaimToken()).isNull();
        assertThat(profile().getLastChangedAt()).isEqualTo(NOW);
        assertThat(profile().getSourceVersion()).isEqualTo(1);
        when(clock.instant()).thenReturn(NOW.plusHours(1).toInstant(ZoneOffset.UTC));
        var next = transactions.prepareDispatch(profileId).orElseThrow();
        assertThat(next.sourceVersion()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void normalHttpResultPreservesNewChange(boolean failure) {
        changeDue();
        var prepared = transactions.prepareDispatch(profileId).orElseThrow();
        update(profile -> {
            profile.recordPreferenceChange(NOW);
            profile.increaseRetryCount();
        });
        if (failure) transactions.applyFailedDispatch(prepared);
        else transactions.applyAcceptedResponse(prepared, accepted(prepared));
        assertThat(profile().getLastChangedAt()).isEqualTo(NOW);
        assertThat(profile().getRetryCount()).isEqualTo(1);
        assertThat(profile().getDispatchClaimToken()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recoveryResultPreservesCallbackOrNewChange(boolean completed) {
        pendingDue();
        var prepared = transactions.prepareRecovery(profileId).orElseThrow();
        update(profile -> {
            if (completed) profile.markCompleted(prepared.sourceVersion());
            else profile.recordPreferenceChange(NOW);
        });
        assertThat(transactions.applyRecoveryResult(prepared))
                .isEqualTo(ProfileDispatchTransactionService.RecoveryDispatchResult.SKIPPED);
        assertThat(profile().getDispatchClaimToken()).isNull();
        if (completed) {
            assertThat(profile().getProfileStatus()).isEqualTo(RecipientProfileStatus.COMPLETED);
            assertThat(profile().getPendingSince()).isNull();
        } else {
            assertThat(profile().getLastChangedAt()).isEqualTo(NOW);
            assertThat(profile().getPendingSince()).isEqualTo(prepared.snapshottedPendingSince());
        }
    }

    @Test
    void repeatedFailureIncreasesRetryOnlyOnce() {
        changeDue();
        var prepared = transactions.prepareDispatch(profileId).orElseThrow();
        transactions.applyFailedDispatch(prepared);
        transactions.applyFailedDispatch(prepared);
        assertThat(profile().getRetryCount()).isEqualTo(1);
        assertThat(profile().getDispatchClaimToken()).isNull();
    }

    @Test
    void expiredButUnreclaimedOwnerCanStillApplyResponse() {
        changeDue();
        var prepared = transactions.prepareDispatch(profileId).orElseThrow();
        expireClaim();
        transactions.applyAcceptedResponse(prepared, accepted(prepared));
        assertThat(profile().getProfileStatus()).isEqualTo(RecipientProfileStatus.PENDING);
        assertThat(profile().getDispatchClaimToken()).isNull();
    }

    @Test
    void httpRunsAfterClaimCommitWithoutHoldingRowLock() throws Exception {
        changeDue();
        when(client.isHealthy()).thenReturn(true);
        var executor = Executors.newSingleThreadExecutor();
        try {
            doAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(profile().getDispatchClaimToken()).isNotNull();
                executor.submit(() -> update(profile -> profile.recordPreferenceChange(NOW)))
                        .get(5, TimeUnit.SECONDS);
                AiProfileRequest request = invocation.getArgument(0);
                return new AiProfileAcceptedResponse(request.recipientUserId(),
                        request.sourceVersion(), RecipientProfileStatus.PENDING);
            }).when(client).requestProfiling(any());
            dispatch.dispatchDueProfiles();
            verify(client, times(1)).requestProfiling(any());
            assertThat(profile().getLastChangedAt()).isEqualTo(NOW);
            assertThat(profile().getDispatchClaimToken()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void heldHttpResponsePreventsSecondExecutorFromSending(boolean recovery) throws Exception {
        if (recovery) pendingDue();
        else changeDue();
        when(client.isHealthy()).thenReturn(true);
        CountDownLatch enteredHttp = new CountDownLatch(1);
        CountDownLatch releaseHttp = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            doAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                enteredHttp.countDown();
                if (!releaseHttp.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("HTTP 응답 해제 대기 시간 초과");
                }
                AiProfileRequest request = invocation.getArgument(0);
                return new AiProfileAcceptedResponse(request.recipientUserId(),
                        request.sourceVersion(), RecipientProfileStatus.PENDING);
            }).when(client).requestProfiling(any());
            Future<?> first = executor.submit(dispatch::dispatchDueProfiles);
            assertThat(enteredHttp.await(5, TimeUnit.SECONDS)).isTrue();
            String token = profile().getDispatchClaimToken();
            assertThat(token).isNotNull();
            // 첫 실행자의 HTTP 응답 대기 중 두 번째 배치를 실행한다.
            dispatch.dispatchDueProfiles();
            verify(client, times(1)).requestProfiling(any());
            assertThat(profile().getDispatchClaimToken()).isEqualTo(token);
            assertThat(profile().getSourceVersion()).isEqualTo(1);
            releaseHttp.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThat(profile().getDispatchClaimToken()).isNull();
        } finally {
            releaseHttp.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void candidateQueryIncludesClaimExactlyAtExpirationBoundary(boolean recovery) {
        if (recovery) {
            pendingDue();
            transactions.prepareRecovery(profileId).orElseThrow();
        } else {
            changeDue();
            transactions.prepareDispatch(profileId).orElseThrow();
        }
        LocalDateTime claimedAt = profile().getDispatchClaimedAt();
        assertThat(profiles.findDispatchCandidateIds(NOW.minusHours(1), NOW.minusHours(6),
                claimedAt.minusNanos(1000), PageRequest.of(0, 100))).doesNotContain(profileId);
        assertThat(profiles.findDispatchCandidateIds(NOW.minusHours(1), NOW.minusHours(6),
                claimedAt, PageRequest.of(0, 100))).contains(profileId);
        if (recovery) {
            assertThat(profiles.findRecoveryCandidateIds(RecipientProfileStatus.PENDING, NOW.minusHours(6),
                    claimedAt.minusNanos(1000), PageRequest.of(0, 100))).doesNotContain(profileId);
            assertThat(profiles.findRecoveryCandidateIds(RecipientProfileStatus.PENDING, NOW.minusHours(6),
                    claimedAt, PageRequest.of(0, 100))).contains(profileId);
        }
    }

    private void changeDue() {
        update(profile -> profile.recordPreferenceChange(NOW.minusHours(2)));
    }

    private void pendingDue() {
        update(profile -> {
            profile.createNextSourceVersion();
            profile.markPending(NOW.minusHours(7));
        });
    }

    private void expireClaim() {
        LocalDateTime expiredAt = profiles.findDispatchClaimNow().minusMinutes(3);
        update(profile -> ReflectionTestUtils.setField(profile, "dispatchClaimedAt", expiredAt));
    }

    private void update(Consumer<RecipientProfile> action) {
        tx.executeWithoutResult(status -> action.accept(profiles.findByIdForUpdate(profileId).orElseThrow()));
    }

    private RecipientProfile profile() {
        return profiles.findById(profileId).orElseThrow();
    }

    private List<Long> normalCandidates() {
        return profiles.findDispatchCandidateIds(NOW.minusHours(1), NOW.minusHours(6),
                profiles.findDispatchClaimNow().minusMinutes(2), PageRequest.of(0, 100));
    }

    private List<Long> recoveryCandidates() {
        return profiles.findRecoveryCandidateIds(RecipientProfileStatus.PENDING, NOW.minusHours(6),
                profiles.findDispatchClaimNow().minusMinutes(2), PageRequest.of(0, 100));
    }

    private AiProfileAcceptedResponse accepted(PreparedProfileDispatch prepared) {
        return new AiProfileAcceptedResponse(prepared.recipientUserId(), prepared.sourceVersion(),
                RecipientProfileStatus.PENDING);
    }

    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<T> firstResult = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("시작 대기 시간 초과");
                return first.call();
            });
            Future<T> secondResult = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("시작 대기 시간 초과");
                return second.call();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
