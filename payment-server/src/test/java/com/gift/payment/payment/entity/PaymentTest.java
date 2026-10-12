package com.gift.payment.payment.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final String REQUEST_HASH = "a".repeat(64);

    @Test
    @DisplayName("대기 중인 결제를 승인 결과와 함께 완료한다")
    void approve_completesPayment() {
        Payment payment = payment();
        String transactionKey = UUID.randomUUID().toString();
        LocalDateTime approvedAt = LocalDateTime.of(2026, 10, 12, 12, 0);

        payment.approve(transactionKey, approvedAt);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getApprovedTransactionKey()).isEqualTo(transactionKey);
        assertThat(payment.getApprovedAt()).isEqualTo(approvedAt);
        assertThat(payment.getFailureCode()).isNull();
    }

    @Test
    @DisplayName("완료된 결제는 다시 승인할 수 없다")
    void approve_rejectsCompletedPayment() {
        Payment payment = payment();

        payment.approve(UUID.randomUUID().toString(), LocalDateTime.now());

        assertThatThrownBy(() -> payment.approve(
                UUID.randomUUID().toString(),
                LocalDateTime.now()
        )).isInstanceOf(IllegalStateException.class);
    }

    private Payment payment() {
        return new Payment(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                REQUEST_HASH,
                64_000L
        );
    }
}
