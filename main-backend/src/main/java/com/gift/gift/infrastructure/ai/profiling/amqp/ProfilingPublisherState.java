package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.rabbitmq.client.BlockedListener;

import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "app.ai-profile.amqp",
        name = "enabled",
        havingValue = "true"
)
public class ProfilingPublisherState implements ConnectionListener {

    private final AtomicBoolean blocked = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong epoch = new AtomicLong();

    private volatile com.rabbitmq.client.Connection current;

    @Override
    public synchronized void onCreate(Connection connection) {
        com.rabbitmq.client.Connection delegate =
                connection.getDelegate();

        current = delegate;
        blocked.set(false);

        delegate.addBlockedListener(new BlockedListener() {
            @Override
            public void handleBlocked(String reason) throws IOException {
                changeBlocked(delegate, true);
            }

            @Override
            public void handleUnblocked() throws IOException {
                changeBlocked(delegate, false);
            }
        });
    }

    @Override
    public synchronized void onClose(Connection connection) {
        if (current == connection.getDelegate()) {
            current = null;
            blocked.set(false);
            epoch.incrementAndGet();
        }
    }

    private synchronized void changeBlocked(
            com.rabbitmq.client.Connection connection,
            boolean value
    ) {
        if (current != connection) {
            return;
        }

        blocked.set(value);
        epoch.incrementAndGet();
    }

    public boolean unavailable() {
        return closing.get() || blocked.get();
    }

    public long epoch() {
        return epoch.get();
    }

    public void beginShutdown() {
        closing.set(true);
        epoch.incrementAndGet();
    }
}
