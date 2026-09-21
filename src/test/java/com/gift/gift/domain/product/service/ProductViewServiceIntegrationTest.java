package com.gift.gift.domain.product.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.gift.gift.domain.product.repository.ProductRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest
class ProductViewServiceIntegrationTest {

    private static final String PASSWORD_HASH =
            "$2a$10$ZUYQG3Jm25Pimrzy9y7/EDJY4bopDMj/XRFMg8QZyGXLc2x7uCU/6";

    @Autowired
    private ProductViewService productViewService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private ProductRepository productRepository;

    private final List<Long> userIds = new ArrayList<>();

    private Long rootCategoryId;
    private Long leafCategoryId;
    private Long productId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();

        jdbcTemplate.update("""
                INSERT INTO categories (name, created_at, updated_at)
                VALUES (?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, "조회수 대분류-" + suffix);
        rootCategoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = ?",
                Long.class,
                "조회수 대분류-" + suffix
        );

        jdbcTemplate.update("""
                INSERT INTO categories (parent_id, name, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, rootCategoryId, "조회수 소분류-" + suffix);
        leafCategoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = ?",
                Long.class,
                "조회수 소분류-" + suffix
        );

        jdbcTemplate.update("""
                INSERT INTO products (
                    category_id, name, brand, description,
                    price, quantity, views, sales, created_at, updated_at
                )
                VALUES (
                    ?, ?, '테스트 브랜드', NULL,
                    10000, 100, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
                )
                """, leafCategoryId, "조회수 테스트 상품-" + suffix);
        productId = jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM products
                WHERE category_id = ? AND name = ?
                """,
                Long.class,
                leafCategoryId,
                "조회수 테스트 상품-" + suffix
        );
    }

    @AfterEach
    void tearDown() {
        reset(productRepository);

        if (productId != null) {
            jdbcTemplate.update(
                    "DELETE FROM product_views WHERE product_id = ?",
                    productId
            );
            jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
        }
        if (leafCategoryId != null) {
            jdbcTemplate.update("DELETE FROM categories WHERE id = ?", leafCategoryId);
        }
        if (rootCategoryId != null) {
            jdbcTemplate.update("DELETE FROM categories WHERE id = ?", rootCategoryId);
        }
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    @DisplayName("최초 조회만 집계하고 24시간 이내 반복 조회는 집계하지 않는다")
    void recordProductView_countsFirstViewOnlyWithinCooldown() {
        Long userId = createUser();

        productViewService.recordProductView(userId, productId);
        LocalDateTime firstViewedAt = lastViewedAt(userId);

        productViewService.recordProductView(userId, productId);

        assertThat(viewCount()).isEqualTo(1);
        assertThat(viewHistoryCount(userId)).isEqualTo(1);
        assertThat(lastViewedAt(userId)).isEqualTo(firstViewedAt);
    }

    @Test
    @DisplayName("23시간 59분이 지나도 다시 집계하지 않는다")
    void recordProductView_doesNotCountBeforeTwentyFourHours() {
        Long userId = createUser();
        productViewService.recordProductView(userId, productId);
        setLastViewedAt("CURRENT_TIMESTAMP(6) - INTERVAL 23 HOUR - INTERVAL 59 MINUTE", userId);
        LocalDateTime beforeRetry = lastViewedAt(userId);

        productViewService.recordProductView(userId, productId);

        assertThat(viewCount()).isEqualTo(1);
        assertThat(lastViewedAt(userId)).isEqualTo(beforeRetry);
    }

    @Test
    @DisplayName("정확히 24시간이 지나면 다시 집계한다")
    void recordProductView_countsAtTwentyFourHourBoundary() {
        Long userId = createUser();
        productViewService.recordProductView(userId, productId);
        setLastViewedAt("CURRENT_TIMESTAMP(6) - INTERVAL 24 HOUR", userId);
        LocalDateTime previousViewedAt = lastViewedAt(userId);

        productViewService.recordProductView(userId, productId);

        assertThat(viewCount()).isEqualTo(2);
        assertThat(lastViewedAt(userId)).isAfter(previousViewedAt);
    }

    @Test
    @DisplayName("날짜가 바뀌어도 24시간이 지나지 않으면 다시 집계하지 않는다")
    void recordProductView_doesNotCountByCalendarDate() {
        Long userId = createUser();
        productViewService.recordProductView(userId, productId);
        setLastViewedAt("CURRENT_DATE - INTERVAL 1 MICROSECOND", userId);
        LocalDateTime previousViewedAt = lastViewedAt(userId);

        productViewService.recordProductView(userId, productId);

        assertThat(viewCount()).isEqualTo(1);
        assertThat(lastViewedAt(userId)).isEqualTo(previousViewedAt);
    }

    @Test
    @DisplayName("동일 사용자와 상품의 동시 최초 조회는 한 번만 집계한다")
    void recordProductView_countsConcurrentFirstViewOnce() throws Exception {
        Long userId = createUser();

        invokeConcurrently(List.of(userId, userId, userId, userId, userId));

        assertThat(viewHistoryCount(userId)).isEqualTo(1);
        assertThat(viewCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("여러 사용자의 동시 조회는 누락 없이 모두 집계한다")
    void recordProductView_countsConcurrentViewsFromDifferentUsers() throws Exception {
        List<Long> concurrentUserIds = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            concurrentUserIds.add(createUser());
        }

        invokeConcurrently(concurrentUserIds);

        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_views WHERE product_id = ?",
                Integer.class,
                productId
        );
        assertThat(historyCount).isEqualTo(concurrentUserIds.size());
        assertThat(viewCount()).isEqualTo(concurrentUserIds.size());
    }

    @Test
    @DisplayName("조회 이력 기록 후 상품 조회 수 증가가 실패하면 함께 롤백한다")
    void recordProductView_rollsBackHistoryWhenViewIncrementFails() {
        Long userId = createUser();
        doThrow(new DataAccessResourceFailureException("의도한 증가 실패"))
                .when(productRepository)
                .incrementViewsIfActive(productId);

        assertThatThrownBy(() ->
                productViewService.recordProductView(userId, productId)
        ).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(viewHistoryCount(userId)).isZero();
        assertThat(viewCount()).isZero();
    }

    private Long createUser() {
        String suffix = UUID.randomUUID().toString();
        String email = "view-" + suffix + "@example.com";

        jdbcTemplate.update("""
                INSERT INTO users (
                    email, password, name, birth, status,
                    is_birthday_public, is_first_login, created_at, updated_at
                )
                VALUES (
                    ?, ?, '조회사용자', '2000-01-01', 'ACTIVE',
                    FALSE, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
                )
                """, email, PASSWORD_HASH);

        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?",
                Long.class,
                email
        );
        userIds.add(userId);
        return userId;
    }

    private void setLastViewedAt(String expression, Long userId) {
        jdbcTemplate.update(
                """
                UPDATE product_views
                SET last_viewed_at = %s
                WHERE user_id = ? AND product_id = ?
                """.formatted(expression),
                userId,
                productId
        );
    }

    private int viewCount() {
        return jdbcTemplate.queryForObject(
                "SELECT views FROM products WHERE id = ?",
                Integer.class,
                productId
        );
    }

    private int viewHistoryCount(Long userId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM product_views
                WHERE user_id = ? AND product_id = ?
                """,
                Integer.class,
                userId,
                productId
        );
    }

    private LocalDateTime lastViewedAt(Long userId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT last_viewed_at
                FROM product_views
                WHERE user_id = ? AND product_id = ?
                """,
                LocalDateTime.class,
                userId,
                productId
        );
    }

    private void invokeConcurrently(List<Long> concurrentUserIds) throws Exception {
        int taskCount = concurrentUserIds.size();
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (Long userId : concurrentUserIds) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 실행 대기 시간 초과");
                    }
                    productViewService.recordProductView(userId, productId);
                    return null;
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
