package com.gift.gift.domain.order.service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PaymentLocalCompletionRetry {

    private static final Logger log = LoggerFactory.getLogger(PaymentLocalCompletionRetry.class);
    private static final long[] RETRY_CAPS_MILLIS = {100L, 200L};

    private final Sleeper sleeper;
    private final LongUnaryOperator random;

    public PaymentLocalCompletionRetry() {
        this(
                Thread::sleep,
                bound -> ThreadLocalRandom.current().nextLong(bound)
        );
    }

    PaymentLocalCompletionRetry(Sleeper sleeper, LongUnaryOperator random) {
        this.sleeper = sleeper;
        this.random = random;
    }

    public <T> T execute(String orderId, Supplier<T> completion) {
        for (int attempt = 0; attempt <= RETRY_CAPS_MILLIS.length; attempt++) {
            try {
                return completion.get();
            } catch (RuntimeException exception) {
                if (LocalPaymentFailureClassifier.classify(exception)
                        != LocalPaymentFailureType.DEADLOCK
                        || attempt == RETRY_CAPS_MILLIS.length) {
                    throw exception;
                }

                waitBeforeRetry(orderId, attempt + 1, exception);
            }
        }

        throw new IllegalStateException("로컬 결제 완료 재시도 흐름이 정상적으로 종료되지 않았습니다.");
    }

    private void waitBeforeRetry(
            String orderId,
            int retryNumber,
            RuntimeException originalException
    ) {
        long capMillis = RETRY_CAPS_MILLIS[retryNumber - 1];
        long halfMillis = capMillis / 2;
        long randomRange = halfMillis + 1;
        long randomMillis = random.applyAsLong(randomRange);

        if (randomMillis < 0 || randomMillis >= randomRange) {
            throw new IllegalStateException("로컬 결제 완료 재시도 난수 범위를 확인해 주세요.");
        }

        long delayMillis = halfMillis + randomMillis;

        log.warn(
                "로컬 결제 완료 트랜잭션의 교착 상태를 재시도합니다. "
                        + "orderId={}, retryNumber={}, delayMs={}",
                orderId,
                retryNumber,
                delayMillis
        );

        try {
            sleeper.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            originalException.addSuppressed(exception);
            throw originalException;
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
