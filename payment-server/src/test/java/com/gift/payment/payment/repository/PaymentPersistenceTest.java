package com.gift.payment.payment.repository;

import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.entity.PaymentCancel;
import com.gift.payment.payment.entity.PaymentCancelStatus;
import com.gift.payment.payment.entity.PaymentStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PaymentPersistenceTest {

    private static final String REQUEST_HASH = "a".repeat(64);

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentCancelRepository paymentCancelRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("같은 주문번호로 결제를 두 번 저장할 수 없다")
    void save_rejectsDuplicateOrderId() {
        String orderId = UUID.randomUUID().toString();

        savePayment(orderId);

        Payment duplicate = new Payment(
                orderId,
                UUID.randomUUID().toString(),
                REQUEST_HASH,
                64_000L
        );

        assertThatThrownBy(
                () -> paymentRepository.saveAndFlush(duplicate)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 결제와 멱등성 키로 취소를 두 번 저장할 수 없다")
    void save_rejectsDuplicateCancelIdempotencyKey() {
        Payment payment = savePayment(UUID.randomUUID().toString());
        String idempotencyKey = "CANCEL-" + UUID.randomUUID();

        paymentCancelRepository.saveAndFlush(
                new PaymentCancel(
                        payment,
                        idempotencyKey,
                        REQUEST_HASH
                )
        );

        PaymentCancel duplicate = new PaymentCancel(
                payment,
                idempotencyKey,
                REQUEST_HASH
        );

        assertThatThrownBy(
                () -> paymentCancelRepository.saveAndFlush(duplicate)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Payment savePayment(String orderId) {
        return paymentRepository.saveAndFlush(
                new Payment(
                        orderId,
                        UUID.randomUUID().toString(),
                        REQUEST_HASH,
                        64_000L
                )
        );
    }
}
