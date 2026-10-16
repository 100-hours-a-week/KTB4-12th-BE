package com.gift.gift.domain.order.service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentLocalCompletionRetryTest {

    @Test
    @DisplayName("교착 상태는 Equal Jitter 대기 후 최대 두 번 재시도한다")
    void execute_retriesDeadlockTwice() {
        List<Long> delays = new ArrayList<>();
        PaymentLocalCompletionRetry retry = new PaymentLocalCompletionRetry(
                delays::add,
                bound -> bound - 1
        );
        AtomicInteger attempts = new AtomicInteger();

        String result = retry.execute("order-1", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw deadlock();
            }
            return "완료";
        });

        assertThat(result).isEqualTo("완료");
        assertThat(attempts).hasValue(3);
        assertThat(delays).containsExactly(100L, 200L);
    }

    @Test
    @DisplayName("교착 상태가 세 번 발생하면 마지막 예외를 반환한다")
    void execute_throwsAfterDeadlockRetriesAreExhausted() {
        List<Long> delays = new ArrayList<>();
        PaymentLocalCompletionRetry retry = new PaymentLocalCompletionRetry(
                delays::add,
                bound -> 0L
        );
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.execute("order-1", () -> {
            attempts.incrementAndGet();
            throw deadlock();
        })).isInstanceOf(RuntimeException.class);

        assertThat(attempts).hasValue(3);
        assertThat(delays).containsExactly(50L, 100L);
    }

    @Test
    @DisplayName("교착 상태가 아닌 오류는 재시도하지 않는다")
    void execute_doesNotRetryOtherFailures() {
        List<Long> delays = new ArrayList<>();
        PaymentLocalCompletionRetry retry = new PaymentLocalCompletionRetry(
                delays::add,
                bound -> 0L
        );
        RuntimeException failure = new RuntimeException("일반 오류");
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.execute("order-1", () -> {
            attempts.incrementAndGet();
            throw failure;
        })).isSameAs(failure);

        assertThat(attempts).hasValue(1);
        assertThat(delays).isEmpty();
    }

    private RuntimeException deadlock() {
        return new RuntimeException(
                new SQLException("교착 상태", "40001", 1213)
        );
    }
}
