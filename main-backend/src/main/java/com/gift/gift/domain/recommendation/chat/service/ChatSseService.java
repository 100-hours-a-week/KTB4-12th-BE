package com.gift.gift.domain.recommendation.chat.service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.recommendation.chat.config.ChatSseProperties;
import com.gift.gift.domain.recommendation.chat.exception.ChatErrorCode;
import com.gift.gift.domain.recommendation.chat.exception.ChatException;
import com.gift.gift.domain.recommendation.chat.model.ChatConversationIdentity;
import com.gift.gift.infrastructure.ai.chat.AiChatSseClient;

@Service
public class ChatSseService {

    private static final int BUFFER_SIZE = 8192;

    private final ChatConversationAccessService access;
    private final AiChatSseClient client;
    private final ExecutorService executor;
    private final Semaphore capacity;

    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopping = new AtomicBoolean();

    public ChatSseService(
            ChatConversationAccessService access,
            AiChatSseClient client,
            ChatSseProperties properties,
            @Qualifier("chatSseExecutor") ExecutorService executor
    ) {
        this.access = access;
        this.client = client;
        this.executor = executor;
        this.capacity = new Semaphore(properties.maxConnections());
    }

    public Session open(
            Long userId,
            String conversationId,
            String token,
            String turnId,
            long cursor
    ) {
        ChatConversationIdentity identity =
                access.authorize(userId, conversationId, token);

        UUID turn = ChatConversationIdentity.parseConversationId(turnId);

        if (stopping.get()) {
            throw new ChatException(ChatErrorCode.AI_SERVICE_UNAVAILABLE);
        }

        if (!capacity.tryAcquire()) {
            throw new ChatException(ChatErrorCode.RATE_LIMIT_EXCEEDED);
        }

        AiChatSseClient.Upstream upstream;

        try {
            upstream = client.open(identity.conversationId(), turn, cursor);
        } catch (RuntimeException exception) {
            capacity.release();
            throw exception;
        }

        Session session = new Session(upstream);
        sessions.add(session);

        if (stopping.get()) {
            session.close();
            throw new ChatException(ChatErrorCode.AI_SERVICE_UNAVAILABLE);
        }

        return session;
    }

    public void execute(Session session, Runnable relay) {
        synchronized (session) {
            if (session.closed) {
                return;
            }

            session.running = true;
        }

        try {
            executor.execute(() -> {
                try {
                    if (!session.isClosed()) {
                        relay.run();
                    }
                } finally {
                    try {
                        session.close();
                    } finally {
                        session.release();
                    }
                }
            });
        } catch (RuntimeException exception) {
            try {
                session.close();
            } finally {
                session.release();
            }

            throw exception;
        }
    }

    @PreDestroy
    public void shutdown() {
        stopping.set(true);
        sessions.forEach(Session::close);
    }

    public int activeConnections() {
        return sessions.size();
    }

    public final class Session implements AutoCloseable {

        private final AiChatSseClient.Upstream upstream;
        private final AtomicBoolean released = new AtomicBoolean();

        private boolean closed;
        private boolean running;

        private Session(AiChatSseClient.Upstream upstream) {
            this.upstream = upstream;
        }

        public synchronized boolean isClosed() {
            return closed;
        }

        public void transferTo(OutputStream output) throws IOException {
            byte[] buffer = new byte[BUFFER_SIZE];

            while (!isClosed()) {
                int count = upstream.body().read(buffer);

                if (count == -1 || isClosed()) {
                    return;
                }

                output.write(buffer, 0, count);
                output.flush();
            }
        }

        @Override
        public void close() {
            boolean releaseNow;

            synchronized (this) {
                if (closed) {
                    return;
                }

                closed = true;
                releaseNow = !running;
            }

            try {
                upstream.close();
            } finally {
                if (releaseNow) {
                    release();
                }
            }
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                sessions.remove(this);
                capacity.release();
            }
        }
    }
}
