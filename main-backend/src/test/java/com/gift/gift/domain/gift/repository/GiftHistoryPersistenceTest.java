package com.gift.gift.domain.gift.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class GiftHistoryPersistenceTest {

    private static final String PASSWORD_HASH = "$2a$10$" + "a".repeat(53);

    @Autowired
    private GiftHistoryRepository giftHistoryRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("정상 선물 이력은 저장된다")
    void save_persistsValidGiftHistory() {
        GiftFixture fixture = persistFixture();

        GiftHistory saved = giftHistoryRepository.saveAndFlush(newGiftHistory(
                fixture.sender(),
                fixture.recipient(),
                fixture.product(),
                1,
                BigDecimal.valueOf(10_000),
                UUID.randomUUID()
        ));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("발신자와 수신자가 같으면 MySQL CHECK 제약이 저장을 거부한다")
    void save_rejectsSameSenderAndRecipient() {
        GiftFixture fixture = persistFixture();

        GiftHistory giftHistory = newGiftHistory(
                fixture.sender(),
                fixture.sender(),
                fixture.product(),
                1,
                BigDecimal.valueOf(10_000),
                UUID.randomUUID()
        );

        assertThatThrownBy(() -> giftHistoryRepository.saveAndFlush(giftHistory))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest(name = "quantity={0}")
    @ValueSource(ints = {0, -1})
    @DisplayName("수량이 0 이하면 MySQL CHECK 제약이 저장을 거부한다")
    void save_rejectsNonPositiveQuantity(int quantity) {
        GiftFixture fixture = persistFixture();

        GiftHistory giftHistory = newGiftHistory(
                fixture.sender(),
                fixture.recipient(),
                fixture.product(),
                quantity,
                BigDecimal.valueOf(10_000),
                UUID.randomUUID()
        );

        assertThatThrownBy(() -> giftHistoryRepository.saveAndFlush(giftHistory))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("가격 스냅샷이 음수면 MySQL CHECK 제약이 저장을 거부한다")
    void save_rejectsNegativePriceSnapshot() {
        GiftFixture fixture = persistFixture();

        GiftHistory giftHistory = newGiftHistory(
                fixture.sender(),
                fixture.recipient(),
                fixture.product(),
                1,
                BigDecimal.valueOf(-1),
                UUID.randomUUID()
        );

        assertThatThrownBy(() -> giftHistoryRepository.saveAndFlush(giftHistory))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 발신자와 멱등성 키 조합은 MySQL UNIQUE 제약이 중복 저장을 거부한다")
    void save_rejectsDuplicateSenderAndIdempotencyKey() {
        GiftFixture fixture = persistFixture();
        UUID idempotencyKey = UUID.randomUUID();
        giftHistoryRepository.saveAndFlush(newGiftHistory(
                fixture.sender(),
                fixture.recipient(),
                fixture.product(),
                1,
                BigDecimal.valueOf(10_000),
                idempotencyKey
        ));

        GiftHistory duplicate = newGiftHistory(
                fixture.sender(),
                fixture.recipient(),
                fixture.product(),
                1,
                BigDecimal.valueOf(10_000),
                idempotencyKey
        );

        assertThatThrownBy(() -> giftHistoryRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private GiftFixture persistFixture() {
        String suffix = UUID.randomUUID().toString();
        Category rootCategory = new Category("대분류-" + suffix, null);
        Category leafCategory = new Category("소분류-" + suffix, rootCategory);
        User sender = newUser("sender-" + suffix);
        User recipient = newUser("recipient-" + suffix);
        Product product = new Product(
                leafCategory,
                "선물 상품",
                "선물 브랜드",
                null,
                BigDecimal.valueOf(10_000),
                100
        );

        entityManager.persist(rootCategory);
        entityManager.persist(leafCategory);
        entityManager.persist(sender);
        entityManager.persist(recipient);
        entityManager.persist(product);
        entityManager.flush();

        return new GiftFixture(sender, recipient, product);
    }

    private User newUser(String emailPrefix) {
        return new User(
                emailPrefix + "@example.com",
                PASSWORD_HASH,
                "김선물",
                LocalDate.of(2000, 1, 1)
        );
    }

    private GiftHistory newGiftHistory(
            User sender,
            User recipient,
            Product product,
            int quantity,
            BigDecimal price,
            UUID idempotencyKey
    ) {
        return new GiftHistory(
                sender,
                recipient,
                product,
                quantity,
                price,
                "선물 상품",
                idempotencyKey,
                "a".repeat(64)
        );
    }

    private record GiftFixture(User sender, User recipient, Product product) {
    }
}
