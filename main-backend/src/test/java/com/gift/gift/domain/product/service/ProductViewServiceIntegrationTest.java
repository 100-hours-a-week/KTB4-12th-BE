package com.gift.gift.domain.product.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gift.gift.domain.product.cache.ProductCacheKeys;
import com.gift.gift.domain.product.cache.ProductViewRedisRepository;
import com.gift.gift.domain.product.exception.ProductException;
import com.gift.gift.domain.product.repository.ProductViewEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@SpringBootTest
class ProductViewServiceIntegrationTest {

    private static final String PASSWORD_HASH = "$2a$10$ZUYQG3Jm25Pimrzy9y7/EDJY4bopDMj/XRFMg8QZyGXLc2x7uCU/6";

    @Autowired
    private ProductViewService productViewService;

    @Autowired
    private ProductDetailService productDetailService;

    @Autowired
    private ProductViewBatchService productViewBatchService;

    @Autowired
    private ProductViewEventRepository productViewEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("productRedisTemplate")
    private StringRedisTemplate redis;

    @MockitoSpyBean
    private ProductViewRedisRepository productViewRedisRepository;

    private final List<Long> userIds = new ArrayList<>();
    private final List<String> recentViewKeys = new ArrayList<>();

    private Long rootCategoryId;
    private Long leafCategoryId;
    private Long productId;

    @BeforeEach
    void setUp() {
        deleteSharedKeys();
        String suffix = UUID.randomUUID().toString();

        jdbcTemplate.update("""
                INSERT INTO categories (name, created_at, updated_at)
                VALUES (?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, "조회수 대분류-" + suffix);
        rootCategoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = ?", Long.class, "조회수 대분류-" + suffix);

        jdbcTemplate.update("""
                INSERT INTO categories (parent_id, name, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, rootCategoryId, "조회수 소분류-" + suffix);
        leafCategoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = ?", Long.class, "조회수 소분류-" + suffix);

        jdbcTemplate.update("""
                INSERT INTO products (
                    category_id, name, brand, description,
                    price, quantity, views, sales, created_at, updated_at
                )
                VALUES (?, ?, '테스트 브랜드', NULL,
                        10000, 100, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, leafCategoryId, "조회수 테스트 상품-" + suffix);
        productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE category_id = ? AND name = ?",
                Long.class, leafCategoryId, "조회수 테스트 상품-" + suffix);
    }

    @AfterEach
    void tearDown() {
        reset(productViewRedisRepository);
        deleteSharedKeys();
        if (!recentViewKeys.isEmpty()) {
            redis.delete(recentViewKeys);
        }

        if (productId != null) {
            jdbcTemplate.update("DELETE FROM product_view_events WHERE product_id = ?", productId);
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
    @DisplayName("상세 조회는 조회수를 Redis에만 올리고 DB 이력과 이벤트는 만들지 않는다")
    void getProductDetail_incrementsInRedisOnly() {
        Long userId = createUser();
        recentViewKeys.add(ProductCacheKeys.recentView(userId, productId));

        assertThat(productDetailService.getProductDetail(userId, productId).product().productId())
                .isEqualTo(productId);

        assertThat(pendingCount()).isEqualTo("1");
        assertThat(countRows("product_views")).isZero();
        assertThat(countRows("product_view_events")).isZero();
        assertThat(viewCount()).isZero();
    }

    @Test
    @DisplayName("같은 사용자의 반복 조회는 한 번만 올린다")
    void recordProductView_ignoresRepeatedView() {
        Long userId = createUser();
        recentViewKeys.add(ProductCacheKeys.recentView(userId, productId));

        productViewService.recordProductView(userId, productId);
        productViewService.recordProductView(userId, productId);

        assertThat(pendingCount()).isEqualTo("1");
    }

    @Test
    @DisplayName("같은 사용자의 동시 최초 조회는 한 번만 올린다")
    void recordProductView_incrementsOnceUnderConcurrency() throws Exception {
        Long userId = createUser();
        recentViewKeys.add(ProductCacheKeys.recentView(userId, productId));
        List<Callable<Void>> calls = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            calls.add(() -> {
                productViewService.recordProductView(userId, productId);
                return null;
            });
        }

        try (ExecutorService executor = Executors.newFixedThreadPool(5)) {
            for (Future<Void> result : executor.invokeAll(calls, 30, TimeUnit.SECONDS)) {
                result.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(pendingCount()).isEqualTo("1");
    }

    @Test
    @DisplayName("Redis 기록이 실패해도 상세 조회는 성공한다")
    void getProductDetail_succeedsWhenRedisFails() {
        Long userId = createUser();
        doThrow(new RedisConnectionFailureException("의도한 Redis 장애"))
                .when(productViewRedisRepository).incrementView(userId, productId);

        assertThat(productDetailService.getProductDetail(userId, productId).product().productId())
                .isEqualTo(productId);

        assertThat(pendingCount()).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 상품 조회는 실패하고 조회수를 올리지 않는다")
    void getProductDetail_skipsWhenProductMissing() {
        Long userId = createUser();
        Long missingProductId = productId + 1_000_000L;

        assertThatThrownBy(() -> productDetailService.getProductDetail(userId, missingProductId))
                .isInstanceOf(ProductException.class);

        verify(productViewRedisRepository, never()).incrementView(anyLong(), anyLong());
        assertThat(redis.hasKey(ProductCacheKeys.pendingViews())).isFalse();
    }

    @Test
    @DisplayName("사용자 ID나 상품 ID가 양수가 아니면 예외를 던지고 올리지 않는다")
    void recordProductView_rejectsInvalidIds() {
        Long userId = createUser();

        assertThatThrownBy(() -> productViewService.recordProductView(0L, productId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("사용자 ID는 양수여야 합니다.");
        assertThatThrownBy(() -> productViewService.recordProductView(userId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("상품 ID는 양수여야 합니다.");

        verify(productViewRedisRepository, never()).incrementView(anyLong(), anyLong());
    }

    @Test
    @DisplayName("상품 생성·삭제·복구 시 통계 상태가 연동되고 레거시 조회수는 갱신하지 않는다")
    void productTriggers_syncStatsLifecycle() {
        assertThat(viewCount()).isZero();
        productViewEventRepository.insertEvent(createUser(), productId);
        productViewBatchService.processPendingEvents();
        assertThat(viewCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT views FROM products WHERE id = ?", Integer.class, productId)).isZero();

        jdbcTemplate.update("UPDATE products SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", productId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM product_view_stats WHERE product_id = ?",
                Boolean.class, productId)).isTrue();
        jdbcTemplate.update("UPDATE products SET deleted_at = NULL WHERE id = ?", productId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NULL FROM product_view_stats WHERE product_id = ?",
                Boolean.class, productId)).isTrue();
        assertThat(viewCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("상품 물리 삭제 시 통계 행도 삭제한다")
    void productTriggers_removeStatsOnDeletion() {
        jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_view_stats WHERE product_id = ?",
                Integer.class, productId)).isZero();
    }

    private Long createUser() {
        String email = "view-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO users (
                    email, password, name, birth, status,
                    is_birthday_public, is_first_login, created_at, updated_at
                )
                VALUES (?, ?, '조회사용자', '2000-01-01', 'ACTIVE',
                        FALSE, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, email, PASSWORD_HASH);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
        userIds.add(userId);
        return userId;
    }

    private String pendingCount() {
        return redis.<String, String>opsForHash()
                .get(ProductCacheKeys.pendingViews(), productId.toString());
    }

    private int viewCount() {
        return jdbcTemplate.queryForObject(
                "SELECT views FROM product_view_stats WHERE product_id = ?",
                Integer.class, productId);
    }

    private int countRows(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE product_id = ?",
                Integer.class, productId);
    }

    private void deleteSharedKeys() {
        redis.delete(List.of(
                ProductCacheKeys.pendingViews(),
                ProductCacheKeys.syncingViews()
        ));
    }
}
