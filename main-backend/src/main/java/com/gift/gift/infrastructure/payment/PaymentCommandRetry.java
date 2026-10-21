package com.gift.gift.infrastructure.payment;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gift.gift.infrastructure.payment.exception.PaymentClientException;
import com.gift.gift.infrastructure.payment.exception.PaymentClientFailure;

@Component
public class PaymentCommandRetry {

    private static final Logger log = LoggerFactory.getLogger(PaymentCommandRetry.class);

    private static final long[] RETRY_CAPS_MILLIS = {1_000L, 2_000L, 4_000L};

    private static final int MAX_RETRIES = RETRY_CAPS_MILLIS.length;

    private final Sleeper sleeper;
    private final LongUnaryOperator random;

    public PaymentCommandRetry() {
        this(
                Thread::sleep,
                bound -> ThreadLocalRandom.current().nextLong(bound)
        );
    }

    PaymentCommandRetry(Sleeper sleeper, LongUnaryOperator random) {
        this.sleeper = sleeper;
        this.random = random;
    }

    public <T> T execute(
            String command,
            String requestKey,
            Supplier<T> request,
            Predicate<T> isProcessing
    ) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            String retryReason;

            try {
                T response = request.get();

                if (!isProcessing.test(response) || attempt == MAX_RETRIES) {
                    return response;
                }

                retryReason = "PROCESSING";
            } catch (PaymentClientException exception) {
                if (!isRetryable(exception.failure()) || attempt == MAX_RETRIES) {
                    throw exception;
                }

                retryReason = exception.failure().name();
            }

            waitBeforeRetry(command, requestKey, attempt + 1, retryReason);
        }

        throw new IllegalStateException("결제 명령 재시도 흐름이 정상적으로 종료되지 않았습니다.");
    }

    private void waitBeforeRetry(
            String command,
            String requestKey,
            int retryNumber,
            String reason
    ) {
        long capMillis = RETRY_CAPS_MILLIS[retryNumber - 1];
        long halfMillis = capMillis / 2;
        long randomRange = halfMillis + 1;
        long randomMillis = random.applyAsLong(randomRange);

        if (randomMillis < 0 || randomMillis >= randomRange) {
            throw new IllegalStateException("결제 명령 재시도 난수 범위를 확인해 주세요.");
        }

        long delayMillis = halfMillis + randomMillis;

        log.warn(
                "Payment Server 명령을 재시도합니다. "
                        + "command={}, requestKey={}, retryNumber={}, "
                        + "delayMs={}, reason={}",
                command,
                requestKey,
                retryNumber,
                delayMillis,
                reason
        );

        try {
            sleeper.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new PaymentClientException(
                    PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE,
                    "Payment Server 재시도 대기가 중단되었습니다.",
                    exception
            );
        }
    }

    private boolean isRetryable(PaymentClientFailure failure) {
        return failure == PaymentClientFailure.PAYMENT_RESULT_UNCONFIRMED
                || failure == PaymentClientFailure.CANCELLATION_RESULT_UNCONFIRMED
                || failure == PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE;
    }

    @FunctionalInterface
    interface Sleeper {

        void sleep(long millis) throws InterruptedException;
    }
}
