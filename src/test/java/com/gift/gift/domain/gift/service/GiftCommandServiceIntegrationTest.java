package com.gift.gift.domain.gift.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.friend.entity.Friend;
import com.gift.gift.domain.gift.dto.request.GiftCreateRequest;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class GiftCommandServiceIntegrationTest {

    private static final String PASSWORD_HASH = "$2a$10$" + "a".repeat(53);

    @Autowired
    private GiftCommandService giftCommandService;

    @Autowired
    private GiftHistoryRepository giftHistoryRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private Long rootCategoryId;
    private Long leafCategoryId;
    private Long productId;
    private Long senderId;
    private Long recipientId;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        String suffix = UUID.randomUUID().toString();

        transaction.executeWithoutResult(status -> {
            Category rootCategory = new Category("생성 트랜잭션 대분류-" + suffix, null);
            Category leafCategory = new Category("생성 트랜잭션 소분류-" + suffix, rootCategory);
            User sender = new User(
                    "sender-" + suffix + "@example.com", PASSWORD_HASH, "발신자", LocalDate.of(1990, 1, 1));
            User recipient = new User(
                    "recipient-" + suffix + "@example.com", PASSWORD_HASH, "수신자", LocalDate.of(1990, 1, 1));
            Product product = new Product(
                    leafCategory, "생성 트랜잭션 상품", "테스트 브랜드", null, BigDecimal.valueOf(32_000), 10);

            entityManager.persist(rootCategory);
            entityManager.persist(leafCategory);
            entityManager.persist(sender);
            entityManager.persist(recipient);
            entityManager.persist(product);
            entityManager.persist(new Friend(sender, recipient));
            entityManager.flush();

            rootCategoryId = rootCategory.getId();
            leafCategoryId = leafCategory.getId();
            productId = product.getId();
            senderId = sender.getId();
            recipientId = recipient.getId();
        });
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM gift_histories WHERE sender_id = ?", senderId);
        jdbcTemplate.update("DELETE FROM friends WHERE user_id = ?", senderId);
        jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", leafCategoryId);
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", rootCategoryId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", senderId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", recipientId);
    }

    @Test
    @DisplayName("재검증을 통과하면 재고를 차감하고 GiftHistory를 저장한다")
    void createNewGift_deductsStockAndPersistsGiftHistory() {
        UUID idempotencyKey = UUID.randomUUID();
        GiftCreateRequest request = new GiftCreateRequest(productId, recipientId, 3, BigDecimal.valueOf(32_000));

        GiftHistory result = giftCommandService.createNewGift(senderId, idempotencyKey, "a".repeat(64), request);

        assertThat(result.getId()).isNotNull();
        assertThat(readProductQuantity()).isEqualTo(7);

        Optional<GiftHistory> saved =
                giftHistoryRepository.findBySender_IdAndIdempotencyKey(senderId, idempotencyKey);
        assertThat(saved).isPresent();
        assertThat(saved.get().getQuantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("멱등 키 UNIQUE 위반으로 저장이 실패하면 같은 트랜잭션의 재고 차감도 함께 롤백된다")
    void createNewGift_rollsBackStockDeduction_whenGiftHistorySaveFails() {
        UUID idempotencyKey = UUID.randomUUID();
        transaction.executeWithoutResult(status -> giftHistoryRepository.saveAndFlush(new GiftHistory(
                entityManager.getReference(User.class, senderId),
                entityManager.getReference(User.class, recipientId),
                entityManager.getReference(Product.class, productId),
                1,
                BigDecimal.valueOf(32_000),
                "선점 이력",
                idempotencyKey,
                "b".repeat(64)
        )));

        int quantityBeforeAttempt = readProductQuantity();
        GiftCreateRequest request = new GiftCreateRequest(productId, recipientId, 2, BigDecimal.valueOf(32_000));

        assertThatThrownBy(() ->
                giftCommandService.createNewGift(senderId, idempotencyKey, "a".repeat(64), request)
        ).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(readProductQuantity()).isEqualTo(quantityBeforeAttempt);
    }

    @Test
    @DisplayName("재고보다 많은 동시 생성 요청이 와도 재고만큼만 성공하고 음수 재고가 되지 않는다")
    void createNewGift_preventsOversellingUnderConcurrency() throws Exception {
        int requestCount = 10;
        int quantityPerRequest = 2;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < requestCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 실행 대기 시간 초과");
                    }

                    GiftCreateRequest request = new GiftCreateRequest(
                            productId, recipientId, quantityPerRequest, BigDecimal.valueOf(32_000));

                    try {
                        giftCommandService.createNewGift(
                                senderId, UUID.randomUUID(), "c".repeat(64), request);
                        return true;
                    } catch (GiftException exception) {
                        if (exception.getErrorCode() == ErrorCode.INSUFFICIENT_STOCK) {
                            return false;
                        }
                        throw exception;
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successCount = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    successCount++;
                }
            }

            assertThat(successCount).isEqualTo(5);
            assertThat(requestCount - successCount).isEqualTo(5);
            assertThat(readProductQuantity()).isZero();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int readProductQuantity() {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM products WHERE id = ?", Integer.class, productId);
    }
}
