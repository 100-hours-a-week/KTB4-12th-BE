package com.gift.gift.domain.recommendation.profiling.service;

import java.net.URI;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;
import org.springframework.data.domain.PageRequest;
import com.gift.gift.domain.recommendation.profiling.repository.RecipientProfileRepository;
import com.gift.gift.domain.recommendation.profiling.support.AiProfilingProperties;
import com.gift.gift.infrastructure.ai.profiling.amqp.ProfilingJobPublisher;
import com.gift.gift.infrastructure.ai.profiling.amqp.ProfilingJobPublisher.TickResult;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfileDispatchServiceTest {
    private final RecipientProfileRepository profiles = mock(RecipientProfileRepository.class);
    private final ProfilingJobPreparationService preparation = mock(ProfilingJobPreparationService.class);
    private final ProfilingJobPublisher publisher = mock(ProfilingJobPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC);
    private final AiProfilingProperties properties = new AiProfilingProperties(
            URI.create("http://unused.invalid"), "test", Duration.ofSeconds(3), Duration.ofSeconds(10),
            Duration.ofSeconds(60), Duration.ofSeconds(300), 100);
    private final ProfileDispatchService service = new ProfileDispatchService(
            profiles, preparation, publisher, properties, clock);

    @Test
    @DisplayName("신규 변경이 없어도 DB에 남은 발행·복구 작업을 최대 100회 순차 처리한다")
    void publishesExistingJobsWithoutNewChangesWithinBatchLimit() {
        when(publisher.publishOne()).thenReturn(TickResult.PROGRESSED);
        service.dispatchDueProfiles();
        verify(publisher, times(100)).publishOne();
        verify(profiles).findJobPreparationCandidateIds(LocalDateTime.now(clock).minusSeconds(60),
                LocalDateTime.now(clock).minusSeconds(300), PageRequest.of(0, 100));
        verifyNoInteractions(preparation);
    }

    @Test
    @DisplayName("한 프로필 준비 실패는 다음 프로필 준비와 기존 작업 발행을 막지 않는다")
    void isolatesPreparationFailureAndPublishesOutsideTransaction() {
        when(profiles.findJobPreparationCandidateIds(any(), any(), any())).thenReturn(List.of(1L, 2L));
        when(preparation.prepare(1L)).thenThrow(new IllegalStateException("invalid input"));
        when(preparation.prepare(2L)).thenReturn(Optional.of("request"));
        when(publisher.publishOne()).thenReturn(TickResult.NO_WORK);
        service.dispatchDueProfiles();
        var order = inOrder(preparation, publisher);
        order.verify(preparation).prepare(1L);
        order.verify(preparation).prepare(2L);
        order.verify(publisher).publishOne();
    }

    @Test
    @DisplayName("작업 없음과 발행 중단 신호를 받으면 추가 선점을 시도하지 않는다")
    void stopsOnNoWorkOrStop() {
        for (TickResult result : List.of(TickResult.NO_WORK, TickResult.STOP)) {
            reset(publisher);
            when(publisher.publishOne()).thenReturn(TickResult.PROGRESSED, result);
            service.dispatchDueProfiles();
            verify(publisher, times(2)).publishOne();
        }
    }

    @Test
    @DisplayName("발행 DB 오류 뒤에도 실행 잠금을 해제해 다음 틱을 허용한다")
    void releasesLockAfterPublisherFailure() {
        when(publisher.publishOne()).thenThrow(new IllegalStateException("db failure"))
                .thenReturn(TickResult.NO_WORK);
        assertThatThrownBy(service::dispatchDueProfiles).isInstanceOf(IllegalStateException.class);
        assertThatCode(service::dispatchDueProfiles).doesNotThrowAnyException();
        verify(publisher, times(2)).publishOne();
    }

    @Test
    @DisplayName("종료 인터럽트가 설정되면 준비·발행을 시작하지 않는다")
    void doesNotStartWhenInterrupted() {
        when(profiles.findJobPreparationCandidateIds(any(), any(), any())).thenReturn(List.of(1L));
        Thread.currentThread().interrupt();
        try {
            service.dispatchDueProfiles();
            verifyNoInteractions(preparation, publisher);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("이전 틱이 실행 중이면 같은 인스턴스의 추가 틱은 겹쳐 실행되지 않는다")
    void skipsOverlappingTick() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(profiles.findJobPreparationCandidateIds(any(), any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return List.of();
        });
        when(publisher.publishOne()).thenReturn(TickResult.NO_WORK);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> first = executor.submit(service::dispatchDueProfiles);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                service.dispatchDueProfiles();
                verifyNoInteractions(publisher);
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }
        verify(profiles).findJobPreparationCandidateIds(any(), any(), any());
        verify(publisher).publishOne();
    }
}
