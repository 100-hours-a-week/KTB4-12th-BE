package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.nio.charset.StandardCharsets;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.recommendation.profiling.repository.ProfilingPublishStore;
import com.gift.gift.domain.recommendation.profiling.repository.ProfilingPublishStore.Claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfilingJobPublisherTest {
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final ProfilingPublishStore repository = mock(ProfilingPublishStore.class);
    private final ProfilingPublisherState state = new ProfilingPublisherState();
    private final Claim claim = new Claim("request-1", 2, "{\"text\":\"한글\"}",
            "attempt-token", "READY", null);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private ProfilingJobPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new ProfilingJobPublisher(template, properties(1), repository, state, metrics);
        when(repository.claimOne()).thenReturn(Optional.of(claim));
    }

    @AfterEach
    void tearDown() {
        publisher.stop();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("ACK이며 Return이 없으면 저장된 UTF-8 본문과 시도 토큰으로 발행 완료를 기록한다")
    void ackWithoutReturn_marksPublishedWithOriginalSnapshot() {
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            assertThat(message.getBody()).isEqualTo(claim.body().getBytes(StandardCharsets.UTF_8));
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(claim.requestId());
            assertThat(correlation.getId()).isEqualTo(claim.token());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(eq(ProfilingAmqpConfiguration.EXCHANGE),
                eq(ProfilingAmqpConfiguration.REQUEST_ROUTING_KEY), any(Message.class), any(CorrelationData.class));

        assertThat(publisher.publishOne()).isEqualTo(ProfilingJobPublisher.TickResult.PROGRESSED);
        verify(repository).markPublished(claim);
        assertThat(metrics.get("profiling.publish.outcomes").tag("code", "ACK_NO_RETURN").counter().count()).isEqualTo(1);
        assertThat(metrics.get("profiling.publish.confirm.wait").timer().count()).isEqualTo(1);
        assertThat(metrics.get("profiling.publish.body.bytes").summary().totalAmount())
                .isEqualTo(claim.body().getBytes(StandardCharsets.UTF_8).length);
        verify(repository, never()).markFailed(any(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("ACK가 와도 Return이 있으면 라우팅 실패로 차단한다")
    void returnedMessage_blocksEvenWithAck() {
        respond(true, true);
        publisher.publishOne();
        verify(repository).markFailed(claim, true, "UNROUTABLE_MESSAGE");
        assertThat(metrics.get("profiling.publish.outcomes").tag("code", "UNROUTABLE_MESSAGE").counter().count()).isEqualTo(1);
        assertThat(metrics.get("profiling.publish.outcomes").tag("code", "ACK_NO_RETURN").counter().count()).isZero();
        verify(repository, never()).markPublished(any());
    }

    @Test
    @DisplayName("NACK는 재시도 가능한 실패로 기록한다")
    void nack_recordsRetryableFailure() {
        respond(false, false);
        publisher.publishOne();
        verify(repository).markFailed(claim, false, "PUBLISH_NACK");
    }

    @Test
    @DisplayName("Confirm이 오지 않으면 제한 시간 후 재시도로 기록한다")
    void missingConfirm_recordsTimeout() {
        publisher.publishOne();
        verify(repository).markFailed(claim, false, "CONFIRM_TIMEOUT");
        verify(repository, never()).markPublished(any());
    }

    @Test
    @DisplayName("인증 실패는 자동 재시도 대신 차단 대상으로 기록한다")
    void authenticationFailure_blocks() {
        doThrow(new AmqpAuthenticationException(new IllegalStateException("denied")))
                .when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publishOne();
        verify(repository).markFailed(claim, true, "BROKER_AUTHENTICATION_FAILED");
    }

    @Test
    @DisplayName("일시 통신 실패는 재시도 대상으로 기록한다")
    void communicationFailure_retries() {
        doThrow(new IllegalStateException("disconnected"))
                .when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publishOne();
        verify(repository).markFailed(claim, false, "BROKER_COMMUNICATION_FAILED");
    }

    @Test
    @DisplayName("UTF-8 기준 256 KiB 초과 본문은 전송하지 않고 차단한다")
    void oversizedUtf8Body_blocksWithoutSend() {
        Claim oversized = new Claim("large", 1, "가".repeat(90_000), "token", "READY", null);
        when(repository.claimOne()).thenReturn(Optional.of(oversized));
        publisher.publishOne();
        verifyNoInteractions(template);
        verify(repository).markFailed(oversized, true, "INPUT_TOO_LARGE");
    }

    @Test
    @DisplayName("발행 성공 후 DB 기록 실패를 통신 실패로 덮어쓰지 않는다")
    void databaseFailure_propagatesForLeaseRecovery() {
        respond(true, false);
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(repository.markPublished(claim)).thenThrow(failure);
        assertThatThrownBy(publisher::publishOne).isSameAs(failure);
        assertThat(metrics.get("profiling.publish.outcomes").tag("code", "ACK_NO_RETURN").counter().count()).isEqualTo(1);
        assertThat(metrics.get("profiling.publish.db.record.errors").counter().count()).isEqualTo(1);
        verify(repository, never()).markFailed(any(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("선점 직후 종료되면 미발행 선점을 반납한다")
    void shutdownAfterClaim_releasesUnsentClaim() {
        when(repository.claimOne()).thenAnswer(invocation -> {
            state.beginShutdown();
            return Optional.of(claim);
        });
        assertThat(publisher.publishOne()).isEqualTo(ProfilingJobPublisher.TickResult.STOP);
        verify(repository).releaseUnsent(claim);
        verifyNoInteractions(template);
    }

    @Test
    @DisplayName("발행할 작업이 없으면 다음 호출에서도 선점할 수 있다")
    void noWork_releasesBusy() {
        when(repository.claimOne()).thenReturn(Optional.empty());
        assertThat(publisher.publishOne()).isEqualTo(ProfilingJobPublisher.TickResult.NO_WORK);
        assertThat(publisher.publishOne()).isEqualTo(ProfilingJobPublisher.TickResult.NO_WORK);
        verify(repository, times(2)).claimOne();
    }

    @Test
    @DisplayName("DB 트랜잭션 안에서 발행하면 즉시 거부한다")
    void activeTransaction_rejectsPublishing() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(publisher::publishOne).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository, template);
    }

    @Test
    @DisplayName("종료 후에는 새로운 작업을 선점하지 않는다")
    void stoppedPublisher_doesNotClaim() {
        publisher.stop();
        assertThat(publisher.publishOne()).isEqualTo(ProfilingJobPublisher.TickResult.STOP);
        verifyNoInteractions(repository, template);
    }

    @Test
    @DisplayName("호출이 중단되어도 실제 워커 종료 전에는 다른 호출이 busy를 해제하지 않는다")
    void interruptedCaller_doesNotReleaseRunningWorkerBusy() throws Exception {
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerReturned = new CountDownLatch(1);
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        doAnswer(invocation -> {
            sending.countDown();
            boolean released = false;
            try {
                while (!released) {
                    try {
                        released = release.await(5, TimeUnit.SECONDS);
                        if (!released) {
                            throw new IllegalStateException("테스트 전송 대기 시간 초과");
                        }
                    } catch (InterruptedException ignored) {
                        // 호출 취소 후에도 실제 I/O가 아직 종료되지 않은 상황을 재현한다.
                    }
                }
                CorrelationData correlation = invocation.getArgument(3);
                correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
                return null;
            } finally {
                workerReturned.countDown();
            }
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        Thread caller = new Thread(() -> {
            try {
                publisher.publishOne();
            } catch (Throwable failure) {
                callerFailure.set(failure);
            }
        });
        try {
            caller.start();
            assertThat(sending.await(3, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(3000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(callerFailure.get()).isNull();
            assertThat(publisher.publishOne()).isEqualTo(ProfilingJobPublisher.TickResult.STOP);
            assertThat(publisher.publishOne()).isEqualTo(ProfilingJobPublisher.TickResult.STOP);
            verify(repository, times(1)).claimOne();
            verify(repository).markFailed(claim, false, "PUBLISH_INTERRUPTED");
            verify(repository, never()).markPublished(any());
        } finally {
            release.countDown();
            caller.join(3000);
            assertThat(workerReturned.await(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("워커 실행 전에 호출이 취소되어도 busy가 해제되어 후속 선점이 가능하다")
    void cancelledBeforeWorkerStarts_releasesBusy() throws Exception {
        CountDownLatch threadCreated = new CountDownLatch(1);
        CountDownLatch startWorker = new CountDownLatch(1);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(publisher, "worker");
        executor.setThreadFactory(task -> new Thread(() -> {
            threadCreated.countDown();
            try {
                if (!startWorker.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("워커 시작 대기 시간 초과");
                }
                task.run();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }));
        Thread caller = new Thread(publisher::publishOne);
        try {
            caller.start();
            assertThat(threadCreated.await(3, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(3000);
            assertThat(caller.isAlive()).isFalse();
            startWorker.countDown();
            AtomicBoolean busy = (AtomicBoolean) ReflectionTestUtils.getField(publisher, "workerBusy");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (busy.get() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(busy.get()).isFalse();
            when(repository.claimOne()).thenReturn(Optional.empty());
            assertThat(publisher.publishOne()).isEqualTo(ProfilingJobPublisher.TickResult.NO_WORK);
            verifyNoInteractions(template);
        } finally {
            startWorker.countDown();
            caller.join(3000);
        }
    }

    @Test
    void staleClaim_isObservedSeparatelyFromBrokerAcceptance() {
        respond(true, false);
        when(repository.markPublished(claim)).thenReturn(false);
        publisher.publishOne();
        assertThat(metrics.get("profiling.publish.stale.updates").counter().count()).isEqualTo(1);
        assertThat(metrics.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).noneSatisfy(tag ->
                        assertThat(tag.getValue()).isIn(claim.requestId(), claim.body(), claim.token())));
    }

    private void respond(boolean ack, boolean returned) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(invocation.getArgument(2), 312,
                        "NO_ROUTE", ProfilingAmqpConfiguration.EXCHANGE, "missing"));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private ProfilingAmqpProperties properties(int confirmSeconds) {
        return new ProfilingAmqpProperties(confirmSeconds, 1);
    }
}
