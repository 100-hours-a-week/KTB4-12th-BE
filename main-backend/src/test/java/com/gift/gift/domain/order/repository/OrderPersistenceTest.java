package com.gift.gift.domain.order.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.entity.PaymentCancellationFailureCode;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional
class OrderPersistenceTest {

    private static final String PASSWORD_HASH = "$2a$10$" + "a".repeat(53);
    private static final String REQUEST_HASH = "a".repeat(64);

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentCancellationRepository paymentCancellationRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("주문과 결제 취소를 저장하면 초기 상태와 참조 정보가 보존된다")
    void save_persistsOrderAndCancellation() {
        TestData data = persistTestData();
        Order order = saveOrder(data, UUID.randomUUID().toString(), "PAYMENT-" + UUID.randomUUID());

        PaymentCancellation saved = paymentCancellationRepository.saveAndFlush(
                new PaymentCancellation(
                        order.getId(),
                        data.payerId(),
                        CancellationSource.USER,
                        "CANCEL-" + UUID.randomUUID(),
                        REQUEST_HASH
                )
        );

        entityManager.clear();

        Order foundOrder = orderRepository.findByOrderId(order.getOrderId()).orElseThrow();
        PaymentCancellation foundCancellation = paymentCancellationRepository.findById(saved.getId()).orElseThrow();

        assertThat(foundOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(foundOrder.getPaymentKey()).isNull();
        assertThat(foundOrder.getApprovalResult()).isNull();
        assertThat(foundCancellation.getOrderRecordId()).isEqualTo(order.getId());
        assertThat(foundCancellation.getStatus()).isEqualTo(PaymentCancellationStatus.PENDING);
        assertThat(foundCancellation.getCancellationSource()).isEqualTo(CancellationSource.USER);
        assertThat(foundCancellation.getCancelTransactionKey()).isNull();
        assertThat(foundCancellation.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 결제자와 멱등성 키로 주문을 두 번 저장할 수 없다")
    void save_rejectsDuplicateOrderIdempotencyKey() {
        TestData data = persistTestData();
        String idempotencyKey = "PAYMENT-" + UUID.randomUUID();

        saveOrder(data, UUID.randomUUID().toString(), idempotencyKey);

        Order duplicate = new Order(
                UUID.randomUUID().toString(),
                data.payerId(),
                data.recipientId(),
                data.productId(),
                2,
                32_000L,
                64_000L,
                idempotencyKey,
                REQUEST_HASH
        );

        assertThatThrownBy(() -> orderRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 요청자와 멱등성 키로 결제 취소를 두 번 저장할 수 없다")
    void save_rejectsDuplicateCancellationIdempotencyKey() {
        TestData data = persistTestData();
        Order order = saveOrder(data, UUID.randomUUID().toString(), "PAYMENT-" + UUID.randomUUID());
        String idempotencyKey = "CANCEL-" + UUID.randomUUID();

        paymentCancellationRepository.saveAndFlush(
                new PaymentCancellation(
                        order.getId(),
                        data.payerId(),
                        CancellationSource.USER,
                        idempotencyKey,
                        REQUEST_HASH
                )
        );

        PaymentCancellation duplicate = new PaymentCancellation(
                order.getId(),
                data.payerId(),
                CancellationSource.USER,
                idempotencyKey,
                REQUEST_HASH
        );

        assertThatThrownBy(() -> paymentCancellationRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("주문과 결제 취소 실패 코드는 VARCHAR 문자열을 Enum으로 조회한다")
    void find_mapsFailureCodesToEnums() {
        TestData data = persistTestData();
        Order order = saveOrder(data, UUID.randomUUID().toString(), "PAYMENT-" + UUID.randomUUID());
        PaymentCancellation cancellation = paymentCancellationRepository.saveAndFlush(
                new PaymentCancellation(
                        order.getId(),
                        data.payerId(),
                        CancellationSource.USER,
                        "CANCEL-" + UUID.randomUUID(),
                        REQUEST_HASH
                )
        );

        jdbcTemplate.update(
                """
                UPDATE orders
                SET status = 'DECLINED',
                    approval_result = 'DECLINED',
                    failure_code = ?
                WHERE id = ?
                """,
                OrderFailureCode.PAYMENT_DECLINED.name(),
                order.getId()
        );
        jdbcTemplate.update(
                """
                UPDATE payment_cancellations
                SET status = 'DECLINED',
                    failure_code = ?
                WHERE id = ?
                """,
                PaymentCancellationFailureCode.CANCELLATION_DECLINED.name(),
                cancellation.getId()
        );
        entityManager.clear();

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getFailureCode())
                .isEqualTo(OrderFailureCode.PAYMENT_DECLINED);
        assertThat(paymentCancellationRepository.findById(cancellation.getId()).orElseThrow().getFailureCode())
                .isEqualTo(PaymentCancellationFailureCode.CANCELLATION_DECLINED);
    }

    @Test
    @DisplayName("정의되지 않은 주문 실패 코드는 DB CHECK 제약이 거부한다")
    void update_rejectsUnknownOrderFailureCode() {
        TestData data = persistTestData();
        Order order = saveOrder(data, UUID.randomUUID().toString(), "PAYMENT-" + UUID.randomUUID());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE orders SET failure_code = 'UNKNOWN_FAILURE' WHERE id = ?",
                order.getId()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_orders_failure_code");
    }

    @Test
    @DisplayName("정의되지 않은 결제 취소 실패 코드는 DB CHECK 제약이 거부한다")
    void update_rejectsUnknownCancellationFailureCode() {
        TestData data = persistTestData();
        Order order = saveOrder(data, UUID.randomUUID().toString(), "PAYMENT-" + UUID.randomUUID());
        PaymentCancellation cancellation = paymentCancellationRepository.saveAndFlush(
                new PaymentCancellation(
                        order.getId(),
                        data.payerId(),
                        CancellationSource.USER,
                        "CANCEL-" + UUID.randomUUID(),
                        REQUEST_HASH
                )
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE payment_cancellations SET failure_code = 'UNKNOWN_FAILURE' WHERE id = ?",
                cancellation.getId()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_payment_cancellations_failure_code");
    }

    private Order saveOrder(TestData data, String orderId, String idempotencyKey) {
        return orderRepository.saveAndFlush(
                new Order(
                        orderId,
                        data.payerId(),
                        data.recipientId(),
                        data.productId(),
                        2,
                        32_000L,
                        64_000L,
                        idempotencyKey,
                        REQUEST_HASH
                )
        );
    }

    private TestData persistTestData() {
        String suffix = UUID.randomUUID().toString();
        User payer = new User(
                "payer-" + suffix + "@example.com",
                PASSWORD_HASH,
                "김결제",
                LocalDate.of(2000, 1, 1)
        );
        User recipient = new User(
                "recipient-" + suffix + "@example.com",
                PASSWORD_HASH,
                "이수신",
                LocalDate.of(2000, 1, 1)
        );
        entityManager.persist(payer);
        entityManager.persist(recipient);

        Category parent = new Category("주문대분류-" + suffix, null);
        entityManager.persist(parent);
        Category category = new Category("주문소분류-" + suffix, parent);
        entityManager.persist(category);

        Product product = new Product(
                category,
                "주문 상품",
                "Example Brand",
                "주문 영속성 테스트 상품",
                BigDecimal.valueOf(32_000L),
                10
        );
        entityManager.persist(product);
        entityManager.flush();

        return new TestData(payer.getId(), recipient.getId(), product.getId());
    }

    private record TestData(Long payerId, Long recipientId, Long productId) {
    }
}
