package com.gift.gift.domain.recommendation.service;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
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
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.infrastructure.ai.AiProfilingClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProfileDispatchPolicyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW =
            Instant.parse("2026-10-07T03:00:00Z");

    private RecipientProfileRepository repository;
    private AiProfilingClient client;
    private Clock clock;
    private ProfileDispatchTransactionService transactions;
    private ProfileDispatchService service;
    private RecipientProfile first;
    private RecipientProfile second;

    @BeforeEach
    void setUp() {
        repository = mock(RecipientProfileRepository.class);
        client = mock(AiProfilingClient.class);
        clock = mock(Clock.class);

        AiProfilingProperties properties = new AiProfilingProperties(
                URI.create("http://ai.example.test"),
                "test-token",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofHours(6),
                100
        );

        setTime(NOW);

        transactions = spy(new ProfileDispatchTransactionService(
                repository,
                mock(UserDislikeCategoryRepository.class),
                properties,
                clock
        ));

        service = new ProfileDispatchService(
                repository,
                transactions,
                client,
                properties,
                clock
        );

        first = profile(1L, 10L);
        second = profile(2L, 20L);

        when(repository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(first));
        when(repository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(second));
        when(repository.findDispatchCandidateIds(any(), any(), any()))
                .thenReturn(List.of(1L, 2L));
        when(client.isHealthy()).thenReturn(true);

        doAnswer(invocation -> {
            AiProfileRequest request = invocation.getArgument(0);
            return accepted(request);
        }).when(client).requestProfiling(any());
    }

    @Test
    @DisplayName("실제 번호 생성 이후에도 첫 실패 횟수를 유지하고 두 번째 실패는 포기한다")
    void dispatch_restartsOnceThenAbandons() {
        doThrow(failure(AiProfilingFailureType.AI_SERVICE_UNAVAILABLE))
                .when(client).requestProfiling(any());

        service.dispatchDueProfiles();

        assertThat(first.getSourceVersion()).isEqualTo(1);
        assertThat(first.getRetryCount()).isEqualTo(1);
        assertThat(first.getLastChangedAt()).isEqualTo(local(NOW));
        assertThat(first.getWindowStartedAt()).isEqualTo(local(NOW));
        assertThat(second.getSourceVersion()).isZero();

        when(repository.findDispatchCandidateIds(any(), any(), any()))
                .thenReturn(List.of(1L));

        setTime(NOW.plusSeconds(59 * 60));
        service.dispatchDueProfiles();

        assertThat(first.getSourceVersion()).isEqualTo(1);

        setTime(NOW.plusSeconds(3600));
        service.dispatchDueProfiles();

        assertThat(first.getSourceVersion()).isEqualTo(2);
        assertThat(first.getLastChangedAt()).isNull();
        assertThat(first.getWindowStartedAt()).isNull();
        assertThat(first.getRetryCount()).isZero();

        service.dispatchDueProfiles();

        verify(client, times(2)).requestProfiling(any());
    }

    @Test
    @DisplayName("PENDING 재변경은 디바운스 뒤 새 번호로 전송한다")
    void dispatch_allowsChangedPendingProfile() {
        service.dispatchDueProfiles();

        assertThat(first.getSourceVersion()).isEqualTo(1);
        assertThat(first.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.PENDING);

        first.recordPreferenceChange(local(NOW.plusSeconds(1)));
        setTime(NOW.plusSeconds(3601));

        service.dispatchDueProfiles();

        assertThat(first.getSourceVersion()).isEqualTo(2);
        assertThat(first.getLastChangedAt()).isNull();
    }

    @Test
    @DisplayName("재시작 대기 중 신규 변경은 횟수를 0으로 초기화한다")
    void change_givesNewRetryOpportunity() {
        first.restartDebounce(local(NOW));
        first.increaseRetryCount();

        first.recordPreferenceChange(local(NOW.plusSeconds(1)));

        assertThat(first.getRetryCount()).isZero();
        assertThat(first.getWindowStartedAt()).isEqualTo(local(NOW));
    }

    @ParameterizedTest
    @EnumSource(
            value = AiProfilingFailureType.class,
            names = {
                    "AI_SERVER_ERROR",
                    "AI_SERVICE_UNAVAILABLE",
                    "COMMUNICATION_ERROR"
            }
    )
    @DisplayName("공통 장애는 첫 대상 실패를 반영하고 나머지 후보를 보존한다")
    void dispatch_stopsTickOnRetryableFailure(
            AiProfilingFailureType type
    ) {
        LocalDateTime unchanged = second.getLastChangedAt();

        doThrow(failure(type)).when(client).requestProfiling(any());

        service.dispatchDueProfiles();

        verify(client, times(1)).requestProfiling(any());
        assertThat(first.getRetryCount()).isEqualTo(1);
        assertThat(second.getSourceVersion()).isZero();
        assertThat(second.getLastChangedAt()).isEqualTo(unchanged);
    }

    @ParameterizedTest
    @EnumSource(
            value = AiProfilingFailureType.class,
            names = {
                    "INVALID_REQUEST",
                    "INVALID_SERVICE_TOKEN",
                    "INVALID_RESPONSE"
            }
    )
    @DisplayName("400·401·응답 오류는 해당 대상 실패 반영 후 다음 대상도 처리한다")
    void dispatch_continuesOnTargetFailure(
            AiProfilingFailureType type
    ) {
        doThrow(failure(type)).when(client).requestProfiling(any());

        service.dispatchDueProfiles();

        verify(client, times(2)).requestProfiling(any());
        assertThat(first.getRetryCount()).isEqualTo(1);
        assertThat(second.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("health 실패 시 후보 조회와 POST 없이 번호를 유지한다")
    void dispatch_skipsBeforeCandidateQueryWhenUnhealthy() {
        when(client.isHealthy()).thenReturn(false);

        service.dispatchDueProfiles();

        verify(repository, never())
                .findDispatchCandidateIds(any(), any(), any());
        verify(client, never()).requestProfiling(any());
        assertThat(first.getSourceVersion()).isZero();
    }

    @Test
    @DisplayName("HTTP 중 새 변경과 재시작이 생기면 옛 실패가 시각과 횟수를 덮지 않는다")
    void failure_preservesNewChange() {
        doAnswer(invocation -> {
            first.recordPreferenceChange(local(NOW.plusSeconds(1)));
            first.restartDebounce(local(NOW.plusSeconds(2)));
            first.increaseRetryCount();
            throw failure(AiProfilingFailureType.AI_SERVICE_UNAVAILABLE);
        }).when(client).requestProfiling(any());

        service.dispatchDueProfiles();

        assertThat(first.getLastChangedAt())
                .isEqualTo(local(NOW.plusSeconds(2)));
        assertThat(first.getWindowStartedAt())
                .isEqualTo(local(NOW.plusSeconds(2)));
        assertThat(first.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("옛 실패는 변경 시각이 같아도 최신 번호를 수정하지 않는다")
    void failure_preservesNewVersion() {
        doAnswer(invocation -> {
            first.createNextSourceVersion();
            throw failure(AiProfilingFailureType.AI_SERVICE_UNAVAILABLE);
        }).when(client).requestProfiling(any());

        LocalDateTime changedAt = first.getLastChangedAt();

        service.dispatchDueProfiles();

        assertThat(first.getSourceVersion()).isEqualTo(2);
        assertThat(first.getLastChangedAt()).isEqualTo(changedAt);
        assertThat(first.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("202의 DB 반영 오류는 전파되고 일반 실패 처리를 호출하지 않는다")
    void accepted_propagatesDatabaseFailure() {
        doThrow(new DataAccessResourceFailureException("DB unavailable"))
                .when(transactions)
                .applyAcceptedResponse(any(), any());

        assertThatThrownBy(service::dispatchDueProfiles)
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(transactions, never()).applyFailedDispatch(any());
    }

    @Test
    @DisplayName("실패의 DB 반영 오류도 전파한다")
    void failure_propagatesDatabaseFailure() {
        doThrow(failure(AiProfilingFailureType.AI_SERVICE_UNAVAILABLE))
                .when(client).requestProfiling(any());

        doThrow(new DataAccessResourceFailureException("DB unavailable"))
                .when(transactions)
                .applyFailedDispatch(any());

        assertThatThrownBy(service::dispatchDueProfiles)
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    @DisplayName("실패 로그에 필요한 필드는 포함하고 예외의 민감 본문은 기록하지 않는다")
    void failure_logsSafeFields() {
        Logger logger = (Logger) LoggerFactory.getLogger(
                ProfileDispatchService.class
        );

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            doThrow(new AiProfilingClientException(
                    AiProfilingFailureType.AI_SERVICE_UNAVAILABLE,
                    "secret-token sensitive-body private@example.com"
            )).when(client).requestProfiling(any());

            service.dispatchDueProfiles();

            assertThat(appender.list).hasSize(1);

            ILoggingEvent event = appender.list.getFirst();

            assertThat(event.getFormattedMessage())
                    .contains(
                            "recipientUserId=10",
                            "sourceVersion=1",
                            "AI_SERVICE_UNAVAILABLE",
                            "DEBOUNCE_RESTARTED"
                    )
                    .doesNotContain(
                            "secret-token",
                            "sensitive-body",
                            "private@example.com"
                    );

            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private RecipientProfile profile(long profileId, long userId) {
        User user = new User(
                "test@example.com",
                "hash",
                "테스트",
                LocalDate.of(2000, 1, 1)
        );

        ReflectionTestUtils.setField(user, "id", userId);

        RecipientProfile profile = new RecipientProfile(user);
        ReflectionTestUtils.setField(profile, "id", profileId);

        profile.recordPreferenceChange(local(NOW.minusSeconds(7200)));
        return profile;
    }

    private void setTime(Instant time) {
        when(clock.instant()).thenReturn(time);
        when(clock.getZone()).thenReturn(ZONE);
    }

    private LocalDateTime local(Instant time) {
        return LocalDateTime.ofInstant(time, ZONE);
    }

    private AiProfilingClientException failure(
            AiProfilingFailureType type
    ) {
        return new AiProfilingClientException(type, "AI failure");
    }

    private AiProfileAcceptedResponse accepted(
            AiProfileRequest request
    ) {
        return new AiProfileAcceptedResponse(
                request.recipientUserId(),
                request.sourceVersion(),
                RecipientProfileStatus.PENDING
        );
    }
}
