package com.gift.gift.domain.recommendation.service;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.preference.repository.UserDislikeCategoryRepository;
import com.gift.gift.domain.recommendation.dto.request.AiProfileRequest;
import com.gift.gift.domain.recommendation.dto.response.AiProfileAcceptedResponse;
import com.gift.gift.domain.recommendation.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.entity.RecipientProfileStatus;
import com.gift.gift.domain.recommendation.exception.AiProfilingClientException;
import com.gift.gift.domain.recommendation.exception.AiProfilingFailureType;
import com.gift.gift.domain.recommendation.repository.RecipientProfileRepository;
import com.gift.gift.domain.recommendation.support.AiProfilingProperties;
import com.gift.gift.domain.recommendation.support.PreparedRecoveryDispatch;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.infrastructure.ai.AiProfilingClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProfileRecoveryPolicyTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-07T03:00:00Z"),
            ZoneOffset.UTC
    );

    private static final LocalDateTime NOW =
            LocalDateTime.now(CLOCK);

    private RecipientProfileRepository repository;
    private UserDislikeCategoryRepository dislikeRepository;
    private AiProfilingClient client;
    private ProfileDispatchTransactionService transactions;
    private ProfileDispatchService service;
    private RecipientProfile profile;

    @BeforeEach
    void setUp() {
        repository = mock(RecipientProfileRepository.class);
        when(repository.findDispatchClaimNow())
                .thenReturn(LocalDateTime.of(2026, 10, 7, 3, 0));
        dislikeRepository = mock(UserDislikeCategoryRepository.class);
        client = mock(AiProfilingClient.class);

        configure(1);

        profile = newProfile(1L, 10L);
        profile.createNextSourceVersion();
        profile.markPending(NOW.minusHours(7));
        profile.increaseRetryCount();

        when(repository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(profile));
        when(repository.findDispatchCandidateIds(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(repository.findRecoveryCandidateIds(any(), any(), any(), any()))
                .thenReturn(List.of(1L));
        when(dislikeRepository.findAllActiveByUserIdWithCategory(any()))
                .thenReturn(List.of());
        when(client.isHealthy()).thenReturn(true);

        doAnswer(invocation -> {
            AiProfileRequest request = invocation.getArgument(0);
            return accepted(request);
        }).when(client).requestProfiling(any());
    }

    @Test
    void recovery_usesSameVersionAndPreservesRetryCount() {
        service.dispatchDueProfiles();

        verify(client).requestProfiling(new AiProfileRequest(
                10L,
                1L,
                List.of()
        ));

        assertThat(profile.getSourceVersion()).isEqualTo(1);
        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.PENDING);
        assertThat(profile.getPendingSince()).isEqualTo(NOW);
        assertThat(profile.getRetryCount()).isEqualTo(1);
        assertThat(profile.getLastChangedAt()).isNull();
        assertThat(profile.getWindowStartedAt()).isNull();
    }

    @Test
    void recovery_failureRestartsOnlyPendingWait() {
        doThrow(failure()).when(client).requestProfiling(any());

        service.dispatchDueProfiles();

        assertThat(profile.getSourceVersion()).isEqualTo(1);
        assertThat(profile.getPendingSince()).isEqualTo(NOW);
        assertThat(profile.getRetryCount()).isEqualTo(1);
        assertThat(profile.getLastChangedAt()).isNull();
        assertThat(profile.getWindowStartedAt()).isNull();
    }

    @Test
    void preparation_rechecksEligibility() {
        profile.recordPreferenceChange(NOW);
        assertThat(transactions.prepareRecovery(1L)).isEmpty();

        profile.clearPendingChange();
        profile.markPending(NOW.minusHours(6).plusSeconds(1));
        assertThat(transactions.prepareRecovery(1L)).isEmpty();

        profile.markPending(NOW.minusHours(6));
        assertThat(transactions.prepareRecovery(1L)).isPresent();

        profile.markCompleted(1);
        assertThat(transactions.prepareRecovery(1L)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateRecoveryResult_preservesCompletedCallback(boolean fail) {
        doAnswer(invocation -> {
            profile.markCompleted(1);

            if (fail) {
                throw failure();
            }

            return accepted(invocation.getArgument(0));
        }).when(client).requestProfiling(any());

        service.dispatchDueProfiles();

        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.COMPLETED);
        assertThat(profile.getPendingSince()).isNull();
        assertThat(profile.getAnalyzedSourceVersion()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateRecoveryResult_preservesNewVersion(boolean fail) {
        LocalDateTime newPendingSince = NOW.plusSeconds(1);

        doAnswer(invocation -> {
            profile.createNextSourceVersion();
            profile.markPending(newPendingSince);

            if (fail) {
                throw failure();
            }

            return accepted(invocation.getArgument(0));
        }).when(client).requestProfiling(any());

        service.dispatchDueProfiles();

        assertThat(profile.getSourceVersion()).isEqualTo(2);
        assertThat(profile.getPendingSince()).isEqualTo(newPendingSince);
        assertThat(profile.getRetryCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recovery_preservesNewChangeAndRestartCount(boolean fail) {
        LocalDateTime changedAt = NOW.plusSeconds(1);
        LocalDateTime originalPendingSince = profile.getPendingSince();

        doAnswer(invocation -> {
            profile.recordPreferenceChange(changedAt);
            profile.restartDebounce(changedAt);
            profile.increaseRetryCount();

            if (fail) {
                throw failure();
            }

            return accepted(invocation.getArgument(0));
        }).when(client).requestProfiling(any());

        service.dispatchDueProfiles();

        assertThat(profile.getSourceVersion()).isEqualTo(1);
        assertThat(profile.getLastChangedAt()).isEqualTo(changedAt);
        assertThat(profile.getWindowStartedAt()).isEqualTo(changedAt);
        assertThat(profile.getRetryCount()).isEqualTo(1);
        assertThat(profile.getPendingSince()).isEqualTo(originalPendingSince);
        assertThat(profile.getDispatchClaimToken()).isNull();
    }

    @Test
    void recovery_skipsResultIfPendingWaitAlreadyChanged() {
        PreparedRecoveryDispatch dispatch =
                transactions.prepareRecovery(1L).orElseThrow();

        LocalDateTime updatedAt = NOW.plusSeconds(1);
        profile.markPending(updatedAt);

        assertThat(transactions.applyRecoveryResult(dispatch))
                .isEqualTo(
                        ProfileDispatchTransactionService
                                .RecoveryDispatchResult.SKIPPED
                );

        assertThat(profile.getPendingSince()).isEqualTo(updatedAt);
    }

    @Test
    void zeroBatch_skipsRecoveryQueryAndPost() {
        configure(0);

        service.dispatchDueProfiles();

        verify(repository, never())
                .findRecoveryCandidateIds(any(), any(), any(), any());
        verify(client, never()).requestProfiling(any());
    }

    @Test
    void batchSize_isPassedToLimitedQuery() {
        service.dispatchDueProfiles();

        verify(repository).findRecoveryCandidateIds(
                eq(RecipientProfileStatus.PENDING),
                eq(NOW.minusHours(6)),
                eq(NOW.minusMinutes(2)),
                eq(org.springframework.data.domain.PageRequest.of(0, 1))
        );
        verify(client, times(1)).requestProfiling(any());
    }

    @Test
    void ordinaryFailure_stopsBeforeRecoveryQuery() {
        RecipientProfile changed = newProfile(2L, 20L);
        changed.recordPreferenceChange(NOW.minusHours(2));

        when(repository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(changed));
        when(repository.findDispatchCandidateIds(any(), any(), any(), any()))
                .thenReturn(List.of(2L));
        doThrow(failure()).when(client).requestProfiling(any());

        service.dispatchDueProfiles();

        verify(repository, never())
                .findRecoveryCandidateIds(any(), any(), any(), any());
        assertThat(profile.getPendingSince())
                .isEqualTo(NOW.minusHours(7));
    }

    @Test
    void recoveryFailure_stopsRemainingCandidates() {
        RecipientProfile second = newProfile(2L, 20L);
        second.createNextSourceVersion();
        second.markPending(NOW.minusHours(8));

        configure(2);

        when(repository.findRecoveryCandidateIds(any(), any(), any(), any()))
                .thenReturn(List.of(1L, 2L));
        when(repository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(second));
        doThrow(failure()).when(client).requestProfiling(any());

        service.dispatchDueProfiles();

        verify(client, times(1)).requestProfiling(any());
        assertThat(profile.getPendingSince()).isEqualTo(NOW);
        assertThat(second.getPendingSince())
                .isEqualTo(NOW.minusHours(8));
    }

    @Test
    void completedOrdinaryRequest_doesNotRestartOnLateFailure() {
        profile.recordPreferenceChange(NOW.minusHours(2));

        when(repository.findDispatchCandidateIds(any(), any(), any(), any()))
                .thenReturn(List.of(1L));
        doAnswer(invocation -> {
            AiProfileRequest request = invocation.getArgument(0);
            profile.markCompleted(request.sourceVersion());
            throw failure();
        }).when(client).requestProfiling(any());

        service.dispatchDueProfiles();

        assertThat(profile.getSourceVersion()).isEqualTo(2);
        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.COMPLETED);
        assertThat(profile.getLastChangedAt()).isNull();
        assertThat(profile.getWindowStartedAt()).isNull();
        assertThat(profile.getRetryCount()).isZero();
    }

    @Test
    void recovery_databaseFailurePropagates() {
        doThrow(new DataAccessResourceFailureException("DB unavailable"))
                .when(transactions).applyRecoveryResult(any());

        assertThatThrownBy(service::dispatchDueProfiles)
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void unhealthy_skipsBothCandidateQueries() {
        when(client.isHealthy()).thenReturn(false);
        service.dispatchDueProfiles();
        verify(repository, never()).findDispatchCandidateIds(any(), any(), any(), any());
        verify(repository, never()).findRecoveryCandidateIds(any(), any(), any(), any());
        verify(client, never()).requestProfiling(any());
        assertThat(profile.getPendingSince()).isEqualTo(NOW.minusHours(7));
    }

    @ParameterizedTest
    @EnumSource(value = AiProfilingFailureType.class,
            names = {"INVALID_REQUEST", "INVALID_SERVICE_TOKEN", "INVALID_RESPONSE"})
    void targetFailure_continuesRecovery(AiProfilingFailureType type) {
        configure(2);
        RecipientProfile second = newProfile(2L, 20L);
        second.createNextSourceVersion();
        second.markPending(NOW.minusHours(8));
        when(repository.findByIdForUpdate(2L)).thenReturn(Optional.of(second));
        when(repository.findRecoveryCandidateIds(any(), any(), any(), any())).thenReturn(List.of(1L, 2L));
        doThrow(new AiProfilingClientException(type, "failure")).when(client).requestProfiling(any());
        service.dispatchDueProfiles();
        verify(client, times(2)).requestProfiling(any());
        assertThat(profile.getPendingSince()).isEqualTo(NOW);
        assertThat(second.getPendingSince()).isEqualTo(NOW);
    }

    private void configure(int recoveryBatchSize) {
        AiProfilingProperties properties = new AiProfilingProperties(
                URI.create("http://ai.example.test"),
                "test-token",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofHours(6),
                100,
                recoveryBatchSize,
                Duration.ofMinutes(2)
        );

        transactions = spy(new ProfileDispatchTransactionService(
                repository,
                dislikeRepository,
                properties,
                CLOCK
        ));

        service = new ProfileDispatchService(
                repository,
                transactions,
                client,
                properties,
                CLOCK
        );
    }

    private RecipientProfile newProfile(long profileId, long recipientId) {
        User user = new User(
                "test@example.com",
                "hash",
                "테스트",
                LocalDate.of(2000, 1, 1)
        );

        ReflectionTestUtils.setField(user, "id", recipientId);

        RecipientProfile result = new RecipientProfile(user);
        ReflectionTestUtils.setField(result, "id", profileId);
        return result;
    }

    private AiProfilingClientException failure() {
        return new AiProfilingClientException(
                AiProfilingFailureType.AI_SERVICE_UNAVAILABLE,
                "AI unavailable"
        );
    }

    private AiProfileAcceptedResponse accepted(AiProfileRequest request) {
        return new AiProfileAcceptedResponse(
                request.recipientUserId(),
                request.sourceVersion(),
                RecipientProfileStatus.PENDING
        );
    }
}
