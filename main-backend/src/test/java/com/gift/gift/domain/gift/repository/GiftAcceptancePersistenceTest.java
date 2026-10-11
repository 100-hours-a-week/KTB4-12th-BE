package com.gift.gift.domain.gift.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        properties = "spring.jpa.hibernate.ddl-auto=validate"
)
@Transactional
class GiftAcceptancePersistenceTest {

    private static final String PASSWORD_HASH = "$2a$10$" + "a".repeat(53);

    private static final String REQUEST_HASH = "a".repeat(64);

    @Autowired
    private GiftHistoryRepository giftHistoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("선물 수락이 먼저 성공하면 이후 취소는 실패한다")
    void complete_preventsLaterCancellation() {
        TestData data = persistTestData();
        LocalDateTime now = LocalDateTime.now().withNano(0);

        GiftHistory gift = giftHistoryRepository.saveAndFlush(
                createWaitingGift(data, UUID.randomUUID(), now.plusDays(7))
        );

        int completedCount = giftHistoryRepository.complete(gift.getId(), data.recipient().getId(), now);

        int canceledCount = giftHistoryRepository.cancel(gift.getId(), now.plusSeconds(1));

        entityManager.clear();

        GiftHistory found = giftHistoryRepository
                .findById(gift.getId())
                .orElseThrow();

        assertThat(completedCount).isOne();
        assertThat(canceledCount).isZero();
        assertThat(found.getStatus())
                .isEqualTo(GiftStatus.COMPLETED);
        assertThat(found.getCompletedAt()).isEqualTo(now);
        assertThat(found.getCanceledAt()).isNull();
    }

    @Test
    @DisplayName("선물 취소가 먼저 성공하면 이후 수락은 실패한다")
    void cancel_preventsLaterAcceptance() {
        TestData data = persistTestData();
        LocalDateTime now = LocalDateTime.now().withNano(0);

        GiftHistory gift = giftHistoryRepository.saveAndFlush(
                createWaitingGift(data, UUID.randomUUID(), now.plusDays(7))
        );

        int canceledCount = giftHistoryRepository.cancel(gift.getId(), now);

        int completedCount = giftHistoryRepository.complete(gift.getId(), data.recipient().getId(), now.plusSeconds(1));

        entityManager.clear();

        GiftHistory found = giftHistoryRepository
                .findById(gift.getId())
                .orElseThrow();

        assertThat(canceledCount).isOne();
        assertThat(completedCount).isZero();
        assertThat(found.getStatus())
                .isEqualTo(GiftStatus.CANCELED);
        assertThat(found.getCompletedAt()).isNull();
        assertThat(found.getCanceledAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("하나의 주문에는 선물을 한 건만 저장할 수 있다")
    void save_rejectsDuplicateOrderGift() {
        TestData data = persistTestData();
        LocalDateTime expiresAt = LocalDateTime.now().withNano(0).plusDays(7);

        giftHistoryRepository.saveAndFlush(
                createWaitingGift(data, UUID.randomUUID(), expiresAt)
        );

        GiftHistory duplicate = createWaitingGift(data, UUID.randomUUID(), expiresAt);

        assertThatThrownBy(
                () -> giftHistoryRepository.saveAndFlush(duplicate)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    private GiftHistory createWaitingGift(
            TestData data,
            UUID idempotencyKey,
            LocalDateTime expiresAt
    ) {
        return GiftHistory.waitingForAcceptance(
                data.orderId(),
                data.sender(),
                data.recipient(),
                data.product(),
                2,
                BigDecimal.valueOf(32_000L),
                "주문 상품",
                idempotencyKey,
                REQUEST_HASH,
                expiresAt
        );
    }

    private TestData persistTestData() {
        String suffix = UUID.randomUUID().toString();

        User sender = new User("sender-" + suffix + "@example.com", PASSWORD_HASH, "김발신", LocalDate.of(2000, 1, 1));

        User recipient = new User("recipient-" + suffix + "@example.com", PASSWORD_HASH, "이수신", LocalDate.of(2000, 1, 1));

        entityManager.persist(sender);
        entityManager.persist(recipient);

        Category parent = new Category("선물대분류-" + suffix, null);
        entityManager.persist(parent);

        Category category = new Category("선물소분류-" + suffix, parent);
        entityManager.persist(category);

        Product product = new Product(
                category,
                "주문 상품",
                "Example Brand",
                "선물 수락 영속성 테스트 상품",
                BigDecimal.valueOf(32_000L),
                10
        );
        entityManager.persist(product);
        entityManager.flush();

        Order order = orderRepository.saveAndFlush(
                new Order(
                        UUID.randomUUID().toString(),
                        sender.getId(),
                        recipient.getId(),
                        product.getId(),
                        2,
                        32_000L,
                        64_000L,
                        "PAYMENT-" + UUID.randomUUID(),
                        REQUEST_HASH
                )
        );

        return new TestData(
                order.getId(),
                sender,
                recipient,
                product
        );
    }

    private record TestData(
            Long orderId,
            User sender,
            User recipient,
            Product product
    ) {
    }
}
