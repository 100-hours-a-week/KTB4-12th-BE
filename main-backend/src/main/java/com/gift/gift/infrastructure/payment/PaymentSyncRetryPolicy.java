package com.gift.gift.infrastructure.payment;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PaymentSyncRetryPolicy {

    public static final int MAX_ADDITIONAL_POST_RETRIES = 3;
    public static final int MAX_STATE_QUERY_RETRIES = 3;

    private static final Logger log = LoggerFactory.getLogger(PaymentSyncRetryPolicy.class);

    private static final Duration MAX_SYNCHRONOUS_PAYMENT_REQUEST_PROCESSING_DURATION = Duration.ofSeconds(10);
    private static final long[] STATE_QUERY_RETRY_CAPS_MILLIS = { 100L, 200L, 400L };

    private final Duration commandTimeout;
    private final Duration queryTimeout;
    private final Sleeper sleeper;
    private final LongUnaryOperator random;

    @Autowired
    public PaymentSyncRetryPolicy(PaymentClientProperties properties) {
        this(
                properties.commandTimeout(),
                properties.queryTimeout(),
                Thread::sleep,
                bound -> ThreadLocalRandom.current().nextLong(bound)
        );
    }

    PaymentSyncRetryPolicy(
            Duration commandTimeout,
            Duration queryTimeout,
            Sleeper sleeper,
            LongUnaryOperator random
    ) {
        this.commandTimeout = commandTimeout;
        this.queryTimeout = queryTimeout;
        this.sleeper = sleeper;
        this.random = random;
    }

    public long deadlineFromNow() {
        return System.nanoTime() + MAX_SYNCHRONOUS_PAYMENT_REQUEST_PROCESSING_DURATION.toNanos();
    }

    public boolean canStartCommand(long deadlineNanos) {
        return remainingNanos(deadlineNanos) >= commandTimeout.plus(queryTimeout).toNanos();
    }

    public boolean canStartQuery(long deadlineNanos) {
        return remainingNanos(deadlineNanos) >= queryTimeout.toNanos();
    }

    public boolean waitBeforeStateQuery(
            long deadlineNanos,
            int retryNumber,
            String requestKey
    ) {
        if (retryNumber < 1 || retryNumber > STATE_QUERY_RETRY_CAPS_MILLIS.length) {
            return false;
        }

        long capMillis = STATE_QUERY_RETRY_CAPS_MILLIS[retryNumber - 1];
        long halfMillis = capMillis / 2;
        long randomRange = halfMillis + 1;
        long randomMillis = random.applyAsLong(randomRange);

        if (randomMillis < 0 || randomMillis >= randomRange) {
            throw new IllegalStateException("결제 상태 조회 재시도 난수 범위를 확인해 주세요.");
        }

        long delayMillis = halfMillis + randomMillis;
        long requiredNanos = Duration.ofMillis(delayMillis).plus(queryTimeout).toNanos();

        if (remainingNanos(deadlineNanos) < requiredNanos) {
            return false;
        }

        log.debug(
                "Payment Server 상태 조회를 재시도합니다. " + "requestKey={}, retryNumber={}, delayMs={}",
                requestKey,
                retryNumber,
                delayMillis
        );

        try {
            sleeper.sleep(delayMillis);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long remainingNanos(long deadlineNanos) {
        return deadlineNanos - System.nanoTime();
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
