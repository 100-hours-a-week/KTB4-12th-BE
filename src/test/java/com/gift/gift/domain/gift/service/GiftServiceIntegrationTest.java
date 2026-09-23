package com.gift.gift.domain.gift.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.friend.entity.Friend;
import com.gift.gift.domain.gift.dto.request.GiftCreateRequest;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GiftServiceIntegrationTest {

    private static final String PASSWORD_HASH = "$2a$10$" + "a".repeat(53);

    @Autowired
    private GiftService giftService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long rootCategoryId;
    private Long leafCategoryId;
    private Long productId;
    private Long senderId;
    private Long recipientId;

    @BeforeEach
    void setUp() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        String suffix = UUID.randomUUID().toString();

        transaction.executeWithoutResult(status -> {
            Category rootCategory = new Category("멱등 복구 대분류-" + suffix, null);
            Category leafCategory = new Category("멱등 복구 소분류-" + suffix, rootCategory);
            User sender = new User(
                    "sender-" + suffix + "@example.com", PASSWORD_HASH, "발신자", LocalDate.of(1990, 1, 1));
            User recipient = new User(
                    "recipient-" + suffix + "@example.com", PASSWORD_HASH, "수신자", LocalDate.of(1990, 1, 1));
            Product product = new Product(
                    leafCategory, "멱등 복구 상품", "테스트 브랜드", null, BigDecimal.valueOf(32_000), 20);

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
    @DisplayName("같은 멱등 키·같은 요청의 동시 생성은 GiftHistory를 한 건만 만들고 모두 같은 결과를 반환한다")
    void createGift_createsOnlyOneGiftHistory_underConcurrentSameIdempotencyKeyRequests() throws Exception {
        int requestCount = 10;
        UUID idempotencyKey = UUID.randomUUID();
        GiftCreateRequest request = new GiftCreateRequest(productId, recipientId, 1, BigDecimal.valueOf(32_000));

        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < requestCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 실행 대기 시간 초과");
                    }

                    GiftHistory result = giftService.createGift(senderId, idempotencyKey, request);
                    return result.getId();
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Set<Long> resultIds = new HashSet<>();
            for (Future<Long> future : futures) {
                resultIds.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(resultIds).hasSize(1);

            // 이 발신자의 GiftHistory는 이번 테스트에서만 생성되므로 sender_id 개수만으로
            // "정확히 한 건만 저장됐다"를 판단할 수 있다(idempotency_key BINARY 인코딩 비교는 불필요).
            Integer savedCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM gift_histories WHERE sender_id = ?", Integer.class, senderId);
            assertThat(savedCount).isEqualTo(1);

            Integer remainingQuantity = jdbcTemplate.queryForObject(
                    "SELECT quantity FROM products WHERE id = ?", Integer.class, productId);
            assertThat(remainingQuantity).isEqualTo(19);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
