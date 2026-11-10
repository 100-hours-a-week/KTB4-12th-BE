package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.BadSqlGrammarException;
import java.sql.SQLException;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;

import com.gift.gift.domain.recommendation.profiling.service.ProfilingResultService;
import com.gift.gift.domain.recommendation.profiling.service.ProfilingResultService.Decision;
import com.gift.gift.domain.recommendation.profiling.service.ProfilingResultService.Reason;
import com.gift.gift.domain.recommendation.profiling.service.ProfilingResultService.Result;
import com.gift.gift.domain.recommendation.profiling.support.ProfilingResultMessageReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProfilingResultMessageListenerTest {

    private static final String REQUEST_ID = "83ae0a27-0ddc-4fb0-a587-35e4937e4f74";
    private final ProfilingResultMessageReader reader = spy(new ProfilingResultMessageReader());
    private final ProfilingResultService service = mock(ProfilingResultService.class);
    private final Channel channel = mock(Channel.class);
    private final AtomicReference<String> haltReason = new AtomicReference<>();
    private final ProfilingResultListener lifecycle = mock(ProfilingResultListener.class);
    private final List<Long> waits = new ArrayList<>();
    private final ProfilingResultMessageListener listener = new ProfilingResultMessageListener(
            reader, service, lifecycle, new SimpleMeterRegistry(), 2,
            millis -> { waits.add(millis); return true; });

    @BeforeEach
    void configureHaltGate() {
        when(lifecycle.isHalted()).thenAnswer(call -> haltReason.get() != null);
        doAnswer(call -> {
            haltReason.compareAndSet(null, call.getArgument(0).toString());
            return null;
        }).when(lifecycle).halt(any(ProfilingResultListener.Reason.class));
    }

    @ParameterizedTest
    @EnumSource(Decision.class)
    @DisplayName("서비스 정상 반환 후에만 메시지별 ACK 또는 재큐잉 없는 거절을 한 번 보낸다")
    void settlesAccordingToCommittedDecision(Decision decision) throws Exception {
        when(service.saveWithCommitObserver(any(), any())).thenReturn(new Result(decision, reason(decision), false, false));
        listener.onMessage(validMessage(), channel);
        var order = inOrder(service, channel);
        order.verify(service).saveWithCommitObserver(any(), any());
        if (decision == Decision.QUARANTINE) {
            order.verify(channel).basicReject(42, false);
        } else {
            order.verify(channel).basicAck(42, false);
        }
        verifyNoMoreInteractions(channel);
        assertThat(haltReason.get()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "null", "[]", "{"})
    @DisplayName("본문 오류는 저장 서비스를 호출하지 않고 해당 메시지만 거절한다")
    void rejectsInvalidBody(String json) throws Exception {
        Message original = validMessage();
        listener.onMessage(new Message(json.getBytes(StandardCharsets.UTF_8), original.getMessageProperties()), channel);
        verifyNoInteractions(service);
        verify(channel).basicReject(42, false);
        verifyNoMoreInteractions(channel);
        assertThat(haltReason.get()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"messageId", "correlationId", "missingMessageId", "missingCorrelationId"})
    @DisplayName("AMQP 식별자 불일치·누락은 본문이 정상이더라도 저장 전에 거절한다")
    void rejectsMismatchedIdentifiers(String field) throws Exception {
        Message message = validMessage();
        var properties = message.getMessageProperties();
        switch (field) {
            case "messageId" -> properties.setMessageId("other");
            case "correlationId" -> properties.setCorrelationId("other");
            case "missingMessageId" -> properties.setMessageId(null);
            case "missingCorrelationId" -> properties.setCorrelationId(null);
            default -> throw new AssertionError(field);
        }
        listener.onMessage(message, channel);
        verifyNoInteractions(service);
        verify(channel).basicReject(42, false);
        verifyNoMoreInteractions(channel);
    }

    @ParameterizedTest
    @ValueSource(strings = {"db", "unknown", "illegalArgument", "parser"})
    @DisplayName("DB·미분류·서비스 인자 오류는 입력 오류로 격리하지 않고 미ACK 중지한다")
    void preservesMessageOnProcessingFailure(String failure) throws Exception {
        if (failure.equals("parser")) {
            doThrow(new IllegalStateException("test-parser-bug")).when(reader).read(any(byte[].class));
        } else {
            RuntimeException exception = switch (failure) {
                case "db" -> new DataAccessResourceFailureException("test-db-down");
                case "illegalArgument" -> new IllegalArgumentException("test-service-bug");
                default -> new IllegalStateException("test-unclassified");
            };
            when(service.saveWithCommitObserver(any(), any())).thenThrow(exception);
        }
        listener.onMessage(validMessage(), channel);
        assertThat(haltReason.get()).isEqualTo("UNCLASSIFIED");
        verifyNoInteractions(channel);
        listener.onMessage(validMessage(), channel);
        verifyNoInteractions(channel);
        verify(reader, times(1)).read(any(byte[].class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("ACK 또는 거절 전송 실패 시 추가 정산 없이 중지하고 재배달에 맡긴다")
    void haltsOnSettlementFailure(boolean rejected) throws Exception {
        when(service.saveWithCommitObserver(any(), any())).thenReturn(new Result(
                rejected ? Decision.QUARANTINE : Decision.RECORDED,
                rejected ? Reason.IDENTITY_MISMATCH : Reason.FINAL_RESULT_RECORDED, false, false));
        if (rejected) {
            doThrow(new IOException("test-channel-down")).when(channel).basicReject(42, false);
        } else {
            doThrow(new IOException("test-channel-down")).when(channel).basicAck(42, false);
        }
        listener.onMessage(validMessage(), channel);
        assertThat(haltReason.get()).isEqualTo("SETTLEMENT");
        if (rejected) {
            verify(channel).basicReject(42, false);
        } else {
            verify(channel).basicAck(42, false);
        }
        verifyNoMoreInteractions(channel);
    }

    @Test
    @DisplayName("채널이 없으면 저장하지 않고 안전 중지를 요청한다")
    void haltsWhenChannelIsMissing() throws Exception {
        listener.onMessage(validMessage(), null);
        assertThat(haltReason.get()).isEqualTo("UNCLASSIFIED");
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("일시 DB 오류는 최초 포함 3회와 1초·2초 대기 후 미ACK 중지한다")
    void boundsTransientDatabaseRetries() throws Exception {
        when(service.saveWithCommitObserver(any(), any()))
                .thenThrow(new CannotAcquireLockException("busy"));
        listener.onMessage(validMessage(), channel);
        verify(service, times(3)).saveWithCommitObserver(any(), any());
        assertThat(waits).containsExactly(1000L, 2000L);
        assertThat(haltReason.get()).isEqualTo("TRANSIENT_DB");
        verifyNoInteractions(channel);
    }

    @Test
    @DisplayName("일시 오류 뒤 저장이 성공하면 커밋 결과에 따라 한 번만 ACK한다")
    void acknowledgesAfterRetrySucceeds() throws Exception {
        when(service.saveWithCommitObserver(any(), any()))
                .thenThrow(new CannotAcquireLockException("busy"))
                .thenReturn(new Result(Decision.RECORDED, Reason.FINAL_RESULT_RECORDED, false, false));
        listener.onMessage(validMessage(), channel);
        verify(service, times(2)).saveWithCommitObserver(any(), any());
        assertThat(waits).containsExactly(1000L);
        verify(channel).basicAck(42, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    @DisplayName("구성 오류는 재시도나 정산 없이 중지한다")
    void doesNotRetryConfigurationFailure() throws Exception {
        when(service.saveWithCommitObserver(any(), any())).thenThrow(
                new BadSqlGrammarException("save", "invalid", new SQLException("bad SQL", "42000")));
        listener.onMessage(validMessage(), channel);
        verify(service).saveWithCommitObserver(any(), any());
        assertThat(waits).isEmpty();
        assertThat(haltReason.get()).isEqualTo("CONFIGURATION");
        verifyNoInteractions(channel);
    }

    @Test
    @DisplayName("미분류 오류는 설정된 최초 포함 2회에서 끝난다")
    void boundsUnclassifiedRetries() throws Exception {
        when(service.saveWithCommitObserver(any(), any())).thenThrow(new IllegalStateException("unknown"));
        listener.onMessage(validMessage(), channel);
        verify(service, times(2)).saveWithCommitObserver(any(), any());
        assertThat(waits).containsExactly(1000L);
        assertThat(haltReason.get()).isEqualTo("UNCLASSIFIED");
        verifyNoInteractions(channel);
    }

    @Test
    @DisplayName("커밋 확인 후 부수 효과가 실패해도 재저장하지 않고 ACK한다")
    void doesNotRetryCommittedResult() throws Exception {
        when(service.saveWithCommitObserver(any(), any())).thenAnswer(call -> {
            Consumer<Result> committed = call.getArgument(1);
            committed.accept(new Result(Decision.RECORDED, Reason.FINAL_RESULT_RECORDED, false, false));
            throw new IllegalStateException("after commit");
        });
        listener.onMessage(validMessage(), channel);
        verify(service).saveWithCommitObserver(any(), any());
        assertThat(waits).isEmpty();
        verify(channel).basicAck(42, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    @DisplayName("대기 중 중지되면 추가 저장이나 ACK를 하지 않는다")
    void cancelsRetryWhenWaitIsStopped() throws Exception {
        var cancellable = new ProfilingResultMessageListener(reader, service, lifecycle,
                new SimpleMeterRegistry(), 2, millis -> false);
        when(service.saveWithCommitObserver(any(), any())).thenThrow(new CannotAcquireLockException("busy"));
        cancellable.onMessage(validMessage(), channel);
        verify(service).saveWithCommitObserver(any(), any());
        verifyNoInteractions(channel);
    }

    private Reason reason(Decision decision) {
        return switch (decision) {
            case RECORDED -> Reason.FINAL_RESULT_RECORDED;
            case DUPLICATE -> Reason.SAME_RESULT;
            case IGNORED -> Reason.SUPERSEDED;
            case QUARANTINE -> Reason.IDENTITY_MISMATCH;
        };
    }

    private Message validMessage() throws IOException {
        try (var input = getClass().getResourceAsStream("/profiling/result-succeeded.json")) {
            var properties = new MessageProperties();
            properties.setMessageId(REQUEST_ID);
            properties.setCorrelationId(REQUEST_ID);
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setDeliveryTag(42);
            return new Message(input.readAllBytes(), properties);
        }
    }
}
