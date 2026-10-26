package com.gift.gift.infrastructure.payment;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.gift.gift.infrastructure.payment.dto.PaymentServerStatus;
import com.gift.gift.infrastructure.payment.exception.PaymentClientException;
import com.gift.gift.infrastructure.payment.exception.PaymentClientFailure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentCommandRetryTest {

    @Test
    @DisplayName("일시 오류는 Equal Jitter 대기 후 최대 세 번 재시도한다")
    void execute_retriesTransientFailureWithEqualJitter() {
        List<Long> delays = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        PaymentCommandRetry retry = new PaymentCommandRetry(
                delays::add,
                bound -> 0
        );

        String result = retry.execute(
                "PAYMENT_CONFIRM",
                "order-id",
                () -> {
                    if (calls.getAndIncrement() < 3) {
                        throw clientException(
                                PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE
                        );
                    }

                    return "COMPLETED";
                },
                response -> false
        );

        assertThat(result).isEqualTo("COMPLETED");
        assertThat(calls).hasValue(4);
        assertThat(delays).containsExactly(500L, 1_000L, 2_000L);
    }

    @Test
    @DisplayName("처리 중 응답은 같은 명령으로 재시도한다")
    void execute_retriesProcessingResponse() {
        List<Long> delays = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        PaymentCommandRetry retry = new PaymentCommandRetry(
                delays::add,
                bound -> bound - 1
        );

        PaymentServerStatus result = retry.execute(
                "PAYMENT_CONFIRM",
                "order-id",
                () -> calls.getAndIncrement() < 2
                        ? PaymentServerStatus.PENDING
                        : PaymentServerStatus.COMPLETED,
                status -> status == PaymentServerStatus.PENDING
        );

        assertThat(result).isEqualTo(PaymentServerStatus.COMPLETED);
        assertThat(calls).hasValue(3);
        assertThat(delays).containsExactly(1_000L, 2_000L);
    }

    @Test
    @DisplayName("재시도 횟수를 소진하면 마지막 처리 중 응답을 반환한다")
    void execute_returnsProcessingAfterRetryExhaustion() {
        List<Long> delays = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        PaymentCommandRetry retry = new PaymentCommandRetry(delays::add, bound -> 0);

        PaymentServerStatus result = retry.execute(
                "PAYMENT_CONFIRM",
                "order-id",
                () -> {
                    calls.incrementAndGet();
                    return PaymentServerStatus.PENDING;
                },
                status -> status == PaymentServerStatus.PENDING
        );

        assertThat(result).isEqualTo(PaymentServerStatus.PENDING);
        assertThat(calls).hasValue(4);
        assertThat(delays).containsExactly(500L, 1_000L, 2_000L);
    }

    @Test
    @DisplayName("재시도 횟수를 소진하면 마지막 일시 오류를 전달한다")
    void execute_throwsAfterRetryExhaustion() {
        List<Long> delays = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        PaymentCommandRetry retry = new PaymentCommandRetry(delays::add, bound -> 0);

        assertThatThrownBy(() -> retry.execute(
                "PAYMENT_CONFIRM",
                "order-id",
                () -> {
                    calls.incrementAndGet();
                    throw clientException(
                            PaymentClientFailure.PAYMENT_RESULT_UNCONFIRMED
                    );
                },
                response -> false
        )).isInstanceOfSatisfying(
                PaymentClientException.class,
                exception -> assertThat(exception.failure())
                        .isEqualTo(
                                PaymentClientFailure.PAYMENT_RESULT_UNCONFIRMED
                        )
        );

        assertThat(calls).hasValue(4);
        assertThat(delays).containsExactly(500L, 1_000L, 2_000L);
    }

    @Test
    @DisplayName("명확한 업무 오류는 재시도하지 않는다")
    void execute_doesNotRetryBusinessFailure() {
        List<Long> delays = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        PaymentCommandRetry retry = new PaymentCommandRetry(delays::add, bound -> 0);

        assertThatThrownBy(() -> retry.execute(
                "PAYMENT_CONFIRM",
                "order-id",
                () -> {
                    calls.incrementAndGet();
                    throw clientException(
                            PaymentClientFailure.PAYMENT_DECLINED
                    );
                },
                response -> false
        )).isInstanceOf(PaymentClientException.class);

        assertThat(calls).hasValue(1);
        assertThat(delays).isEmpty();
    }

    @Test
    @DisplayName("취소 결과 미확정도 같은 취소 명령으로 재시도한다")
    void execute_retriesUnconfirmedCancellation() {
        AtomicInteger calls = new AtomicInteger();

        PaymentCommandRetry retry = new PaymentCommandRetry(
                ignored -> {
                },
                bound -> 0
        );

        String result = retry.execute(
                "PAYMENT_CANCEL",
                "payment-key",
                () -> {
                    if (calls.getAndIncrement() == 0) {
                        throw clientException(
                                PaymentClientFailure
                                        .CANCELLATION_RESULT_UNCONFIRMED
                        );
                    }

                    return "CANCELED";
                },
                response -> false
        );

        assertThat(result).isEqualTo("CANCELED");
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("재시도 대기가 중단되면 인터럽트를 복원하고 호출을 중단한다")
    void execute_stopsWhenSleepIsInterrupted() {
        AtomicInteger calls = new AtomicInteger();

        PaymentCommandRetry retry = new PaymentCommandRetry(
                ignored -> {
                    throw new InterruptedException();
                },
                bound -> 0
        );

        try {
            assertThatThrownBy(() -> retry.execute(
                    "PAYMENT_CONFIRM",
                    "order-id",
                    () -> {
                        calls.incrementAndGet();

                        throw clientException(
                                PaymentClientFailure
                                        .PAYMENT_SERVER_UNAVAILABLE
                        );
                    },
                    response -> false
            )).isInstanceOfSatisfying(
                    PaymentClientException.class,
                    exception -> assertThat(exception.failure())
                            .isEqualTo(
                                    PaymentClientFailure
                                            .PAYMENT_SERVER_UNAVAILABLE
                            )
            );

            assertThat(calls).hasValue(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentClientFailure.class,
            names = {"INVALID_REQUEST", "AUTHENTICATION_FAILED", "PAYMENT_DECLINED"})
    @DisplayName("첫 요청의 명확한 거절은 미확정 이력 없이 그대로 전달한다")
    void execute_firstRejectionHasNoUncertainHistory(PaymentClientFailure failure) {
        List<Long> delays = new ArrayList<>();
        PaymentCommandRetry retry = new PaymentCommandRetry(delays::add, bound -> 0);
        PaymentClientException original = clientException(failure);

        assertThatThrownBy(() -> retry.execute("PAYMENT_CONFIRM", "order-id", () -> {
            throw original;
        }, response -> false)).isInstanceOfSatisfying(PaymentClientException.class, error -> {
            assertThat(error).isSameAs(original);
            assertThat(error.outcomeUncertain()).isFalse();
        });
        assertThat(delays).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentClientFailure.class,
            names = {"INVALID_REQUEST", "AUTHENTICATION_FAILED", "PAYMENT_DECLINED",
                    "CANCELLATION_DECLINED"})
    @DisplayName("앞선 타임아웃 뒤 거절은 원본 오류와 추적값을 보존하고 결과 미확정으로 표시한다")
    void execute_rejectionAfterTimeoutKeepsUncertainHistory(PaymentClientFailure failure) {
        AtomicInteger calls = new AtomicInteger();
        PaymentCommandRetry retry = new PaymentCommandRetry(ignored -> {}, bound -> 0);
        PaymentClientException original = new PaymentClientException(failure, "거절", "upstream-trace");

        assertThatThrownBy(() -> retry.execute("PAYMENT_CONFIRM", "order-id", () -> {
            if (calls.getAndIncrement() == 0) {
                throw clientException(PaymentClientFailure.PAYMENT_RESULT_UNCONFIRMED);
            }
            throw original;
        }, response -> false)).isInstanceOfSatisfying(PaymentClientException.class, error -> {
            assertThat(error.outcomeUncertain()).isTrue();
            assertThat(error.failure()).isEqualTo(failure);
            assertThat(error.upstreamTraceId()).isEqualTo("upstream-trace");
            assertThat(error.getCause()).isSameAs(original);
        });
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("처리 중 응답 뒤 입력 거절도 앞선 승인 결과를 미확정으로 보존한다")
    void execute_rejectionAfterProcessingKeepsUncertainHistory() {
        AtomicInteger calls = new AtomicInteger();
        PaymentCommandRetry retry = new PaymentCommandRetry(ignored -> {}, bound -> 0);

        assertThatThrownBy(() -> retry.execute("PAYMENT_CONFIRM", "order-id", () -> {
            if (calls.getAndIncrement() == 0) {
                return PaymentServerStatus.PENDING;
            }
            throw clientException(PaymentClientFailure.INVALID_REQUEST);
        }, status -> status == PaymentServerStatus.PENDING))
                .isInstanceOfSatisfying(PaymentClientException.class,
                        error -> assertThat(error.outcomeUncertain()).isTrue());
        assertThat(calls).hasValue(2);
    }

    private PaymentClientException clientException(
            PaymentClientFailure failure
    ) {
        return new PaymentClientException(failure, "테스트 오류", (String) null);
    }
}
