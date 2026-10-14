package com.gift.gift.domain.recommendation.chat.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.gift.gift.domain.recommendation.chat.config.ChatSseConfig;
import com.gift.gift.domain.recommendation.chat.config.ChatSseProperties;
import com.gift.gift.domain.recommendation.chat.exception.ChatException;
import com.gift.gift.domain.recommendation.chat.model.ChatConversationIdentity;
import com.gift.gift.domain.recommendation.chat.exception.ChatErrorCode;
import com.gift.gift.infrastructure.ai.chat.AiChatSseClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Timeout(10)
class ChatSseServiceTest {

    private static final UUID CONVERSATION = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID TURN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final ChatConversationAccessService access = mock(ChatConversationAccessService.class);
    private final AiChatSseClient client = mock(AiChatSseClient.class);
    private final AiChatSseClient.Upstream upstream = mock(AiChatSseClient.Upstream.class);
    private ExecutorService executor;
    private ChatSseService service;

    @BeforeEach
    void setUp() {
        executor = new ChatSseConfig().chatSseExecutor();
        service = new ChatSseService(access, client,
                new ChatSseProperties(Duration.ofSeconds(60), Duration.ofSeconds(240), 1), executor);
        when(access.authorize(1L, CONVERSATION.toString(), "token"))
                .thenReturn(new ChatConversationIdentity(CONVERSATION, 1L, 2L));
        when(client.open(CONVERSATION, TURN, 0)).thenReturn(upstream);
    }

    @AfterEach
    void tearDown() throws Exception {
        service.shutdown();
        executor.shutdownNow();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("중계 작업은 가상 스레드에서 실행하고 종료 시 연결을 정리한다")
    void execute_usesVirtualThread() throws Exception {
        var session = open();
        AtomicBoolean virtual = new AtomicBoolean();
        service.execute(session, () -> virtual.set(Thread.currentThread().isVirtual()));
        executor.shutdown();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        assertThat(virtual.get()).isTrue();
        assertThat(service.activeConnections()).isZero();
        verify(upstream).close();
    }

    @Test
    @DisplayName("연결을 닫아도 실행 중인 중계 작업이 끝나기 전에는 슬롯을 반환하지 않는다")
    void close_holdsCapacityUntilTaskExits() throws Exception {
        var session = open();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        service.execute(session, () -> {
            running.countDown();
            try {
                finish.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat(running.await(1, TimeUnit.SECONDS)).isTrue();
            session.close();
            assertThat(service.activeConnections()).isEqualTo(1);
            assertThatThrownBy(this::open).isInstanceOfSatisfying(ChatException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(ChatErrorCode.RATE_LIMIT_EXCEEDED.errorCode()));
        } finally {
            finish.countDown();
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        assertThat(service.activeConnections()).isZero();
        try (var next = open()) {
            assertThat(next.isClosed()).isFalse();
        }
    }

    @Test
    @DisplayName("실행기가 작업을 거절해도 upstream과 슬롯을 정리한다")
    void execute_releasesCapacityWhenExecutorRejects() {
        var session = open();
        executor.shutdown();
        assertThatThrownBy(() -> service.execute(session, () -> {}))
                .isInstanceOf(RejectedExecutionException.class);
        verify(upstream).close();
        assertThat(service.activeConnections()).isZero();
        try (var next = open()) {
            assertThat(next.isClosed()).isFalse();
        }
    }

    @Test
    @DisplayName("서버 종료는 열린 연결을 닫고 새 AI 연결을 거절한다")
    void shutdown_closesSessionsAndRejectsNewConnections() {
        var session = open();
        service.shutdown();
        assertThat(session.isClosed()).isTrue();
        assertThat(service.activeConnections()).isZero();
        verify(upstream).close();
        assertThatThrownBy(this::open).isInstanceOfSatisfying(ChatException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ChatErrorCode.AI_SERVICE_UNAVAILABLE.errorCode()));
    }

    @Test
    @DisplayName("실행 전에 종료된 연결은 중계 작업을 시작하지 않는다")
    void execute_skipsAlreadyClosedSession() {
        var session = open();
        session.close();
        Runnable relay = mock(Runnable.class);
        service.execute(session, relay);
        verifyNoInteractions(relay);
        assertThat(service.activeConnections()).isZero();
        verify(upstream).close();
    }

    @Test
    @DisplayName("upstream 읽기 오류와 FE 쓰기 오류를 숨기지 않고 호출자에게 전달한다")
    void transferTo_propagatesReadAndWriteFailures() throws Exception {
        InputStream input = mock(InputStream.class);
        when(upstream.body()).thenReturn(input);
        when(input.read(any(byte[].class)))
                .thenThrow(new IOException("upstream disconnected"));
        try (var session = open()) {
            assertThatThrownBy(() -> session.transferTo(OutputStream.nullOutputStream()))
                    .isInstanceOf(IOException.class).hasMessage("upstream disconnected");
        }
        doReturn(1).when(input).read(any(byte[].class));
        OutputStream output = mock(OutputStream.class);
        doThrow(new IOException("frontend disconnected"))
                .when(output).write(any(byte[].class),
                        eq(0), eq(1));
        try (var session = open()) {
            assertThatThrownBy(() -> session.transferTo(output))
                    .isInstanceOf(IOException.class).hasMessage("frontend disconnected");
        }
        assertThat(service.activeConnections()).isZero();
    }

    @Test
    @DisplayName("AI 응답 헤더를 기다리는 연결도 상한에 포함한다")
    void open_reservesCapacityBeforeUpstreamResponds() throws Exception {
        CountDownLatch connecting = new CountDownLatch(1);
        CountDownLatch respond = new CountDownLatch(1);
        when(client.open(CONVERSATION, TURN, 0)).thenAnswer(invocation -> {
            connecting.countDown();
            if (!respond.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("upstream response timeout");
            }
            return upstream;
        });
        var preparing = executor.submit(this::open);
        try {
            assertThat(connecting.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(this::open).isInstanceOfSatisfying(ChatException.class,
                    exception -> assertThat(exception.getErrorCode())
                            .isEqualTo(ChatErrorCode.RATE_LIMIT_EXCEEDED.errorCode()));
            verify(client, times(1)).open(CONVERSATION, TURN, 0);
        } finally {
            respond.countDown();
        }
        try (var session = preparing.get(2, TimeUnit.SECONDS)) {
            assertThat(session.isClosed()).isFalse();
        }
        assertThat(service.activeConnections()).isZero();
    }

    private ChatSseService.Session open() {
        return service.open(1L, CONVERSATION.toString(), "token", TURN.toString(), 0);
    }
}
