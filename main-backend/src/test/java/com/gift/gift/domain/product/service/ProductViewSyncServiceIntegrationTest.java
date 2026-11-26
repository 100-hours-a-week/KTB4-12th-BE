package com.gift.gift.domain.product.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.gift.gift.domain.product.cache.ProductCacheKeys;
import com.gift.gift.domain.product.cache.ProductViewRedisRepository;
import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.product.repository.ProductViewStatsRepository;
import com.gift.gift.domain.product.search.ProductSearchOutboxWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest
class ProductViewSyncServiceIntegrationTest {

    @Autowired
    private ProductViewSyncService productViewSyncService;

    @Autowired
    private ProductViewRedisRepository productViewRedisRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductSearchOutboxWriter outboxWriter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("productRedisTemplate")
    private StringRedisTemplate redis;

    @MockitoSpyBean
    private ProductViewStatsRepository productViewStatsRepository;

    private final List<Long> productIds = new ArrayList<>();
    private final List<String> recentViewKeys = new ArrayList<>();

    private Long rootCategoryId;
    private Long leafCategoryId;

    @BeforeEach
    void setUp() {
        deleteSharedKeys();
        String suffix = UUID.randomUUID().toString();

        jdbcTemplate.update("""
                INSERT INTO categories (name, created_at, updated_at)
                VALUES (?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, "Sync 대분류-" + suffix);
        rootCategoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = ?", Long.class, "Sync 대분류-" + suffix);
        jdbcTemplate.update("""
                INSERT INTO categories (parent_id, name, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, rootCategoryId, "Sync 소분류-" + suffix);
        leafCategoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = ?", Long.class, "Sync 소분류-" + suffix);
    }

    @AfterEach
    void tearDown() {
        reset(productViewStatsRepository);
        deleteSharedKeys();
        if (!recentViewKeys.isEmpty()) {
            redis.delete(recentViewKeys);
        }
        for (Long productId : productIds) {
            jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
        }
        if (leafCategoryId != null) {
            jdbcTemplate.update("DELETE FROM categories WHERE id = ?", leafCategoryId);
        }
        if (rootCategoryId != null) {
            jdbcTemplate.update("DELETE FROM categories WHERE id = ?", rootCategoryId);
        }
    }

    @Test
    @DisplayName("Redis 조회수를 상품별로 통계에 반영하고 Redis 조회수를 비운다")
    void sync_appliesViewCounts() {
        Long firstProductId = createProduct();
        Long secondProductId = createProduct();
        addViews(firstProductId, 3);
        addViews(secondProductId, 2);

        assertThat(productViewSyncService.sync()).isEqualTo(2);

        assertThat(viewCount(firstProductId)).isEqualTo(3);
        assertThat(viewCount(secondProductId)).isEqualTo(2);
        assertThat(searchVersion(firstProductId)).isEqualTo(1);
        assertThat(searchVersion(secondProductId)).isEqualTo(1);
        assertThat(redis.hasKey(ProductCacheKeys.pendingViews())).isFalse();
        assertThat(redis.hasKey(ProductCacheKeys.syncingViews())).isFalse();
        assertThat(productViewSyncService.sync()).isZero();
        assertThat(viewCount(firstProductId)).isEqualTo(3);
    }

    @Test
    @DisplayName("삭제된 상품의 조회수는 반영하지 않고 버린다")
    void sync_skipsInactiveProduct() {
        Long productId = createProduct();
        addViews(productId, 2);
        jdbcTemplate.update("UPDATE products SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", productId);

        assertThat(productViewSyncService.sync()).isZero();

        assertThat(viewCount(productId)).isZero();
        assertThat(redis.hasKey(ProductCacheKeys.syncingViews())).isFalse();
    }

    @Test
    @DisplayName("통계 행이 없는 상품은 건너뛰고 나머지는 반영한다")
    void sync_skipsMissingStats() {
        Long productWithoutStatsId = createProduct();
        Long normalProductId = createProduct();
        jdbcTemplate.update("DELETE FROM product_view_stats WHERE product_id = ?", productWithoutStatsId);
        addViews(productWithoutStatsId, 1);
        addViews(normalProductId, 4);

        assertThat(productViewSyncService.sync()).isEqualTo(1);

        assertThat(viewCount(normalProductId)).isEqualTo(4);
        assertThat(redis.hasKey(ProductCacheKeys.syncingViews())).isFalse();
    }

    @Test
    @DisplayName("DB 갱신이 실패하면 롤백하고 Redis 조회수를 남겨 다음 실행에서 반영한다")
    void sync_keepsViewCountsOnDatabaseFailure() {
        Long productId = createProduct();
        addViews(productId, 3);
        doThrow(new DataAccessResourceFailureException("의도한 통계 갱신 실패"))
                .when(productViewStatsRepository).incrementViewsIfActive(productId, 3);

        assertThatThrownBy(() -> productViewSyncService.sync())
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(viewCount(productId)).isZero();
        assertThat(searchVersion(productId)).isZero();
        assertThat(syncingCount(productId)).isEqualTo("3");

        reset(productViewStatsRepository);
        assertThat(productViewSyncService.sync()).isEqualTo(1);
        assertThat(viewCount(productId)).isEqualTo(3);
        assertThat(redis.hasKey(ProductCacheKeys.syncingViews())).isFalse();
    }

    @Test
    @DisplayName("남아 있던 syncing을 먼저 반영하고 그 다음 실행에서 pending을 반영한다")
    void sync_resumesLeftoverSyncing() {
        Long leftoverProductId = createProduct();
        Long newProductId = createProduct();
        redis.opsForHash().put(ProductCacheKeys.syncingViews(), leftoverProductId.toString(), "2");
        addViews(newProductId, 1);

        assertThat(productViewSyncService.sync()).isEqualTo(1);
        assertThat(viewCount(leftoverProductId)).isEqualTo(2);
        assertThat(viewCount(newProductId)).isZero();
        assertThat(redis.<String, String>opsForHash()
                .get(ProductCacheKeys.pendingViews(), newProductId.toString())).isEqualTo("1");

        assertThat(productViewSyncService.sync()).isEqualTo(1);
        assertThat(viewCount(newProductId)).isEqualTo(1);
    }

    @Test
    @DisplayName("묶음마다 커밋하고 실패한 묶음부터 Redis에 남긴다")
    void sync_commitsPerChunk() {
        List<Long> targetIds = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            Long productId = createProduct();
            targetIds.add(productId);
            addViews(productId, 1);
        }
        Long failingProductId = targetIds.get(4);
        doThrow(new DataAccessResourceFailureException("의도한 통계 갱신 실패"))
                .when(productViewStatsRepository).incrementViewsIfActive(failingProductId, 1);
        ProductViewSyncService serviceWithChunkOfTwo = new ProductViewSyncService(
                productViewRedisRepository, productRepository, productViewStatsRepository,
                outboxWriter, transactionManager, new SimpleMeterRegistry(), 2);

        assertThatThrownBy(serviceWithChunkOfTwo::sync)
                .isInstanceOf(DataAccessResourceFailureException.class);

        for (int index = 0; index < 4; index++) {
            assertThat(viewCount(targetIds.get(index))).isEqualTo(1);
        }
        assertThat(viewCount(failingProductId)).isZero();
        assertThat(redis.<String, String>opsForHash().entries(ProductCacheKeys.syncingViews()))
                .containsOnlyKeys(failingProductId.toString());
    }

    @Test
    @DisplayName("지표는 반영한 상품 수, 마지막 성공 시각, pending 상품 수를 기록한다")
    void sync_recordsMetrics() {
        Long firstProductId = createProduct();
        Long secondProductId = createProduct();
        addViews(firstProductId, 1);
        addViews(secondProductId, 1);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ProductViewSyncService service = new ProductViewSyncService(
                productViewRedisRepository, productRepository, productViewStatsRepository,
                outboxWriter, transactionManager, meterRegistry, 100);

        assertThat(meterRegistry.get("gift.product.view.pending").gauge().value()).isEqualTo(2.0);
        assertThat(meterRegistry.get("gift.product.view.sync.last.success").gauge().value()).isZero();

        assertThat(service.sync()).isEqualTo(2);

        assertThat(meterRegistry.get("gift.product.view.synced").counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.get("gift.product.view.sync").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.get("gift.product.view.sync.last.success").gauge().value()).isPositive();
        assertThat(meterRegistry.get("gift.product.view.pending").gauge().value()).isZero();
    }

    private Long createProduct() {
        String name = "Sync 상품-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO products (
                    category_id, name, brand, description,
                    price, quantity, sales, created_at, updated_at
                )
                VALUES (?, ?, '테스트 브랜드', NULL,
                        10000, 100, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, leafCategoryId, name);
        Long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE name = ?", Long.class, name);
        productIds.add(productId);
        return productId;
    }

    private void addViews(Long productId, int count) {
        for (int index = 0; index < count; index++) {
            long userId = ThreadLocalRandom.current().nextLong(1_000_000L, 1_000_000_000_000L);
            recentViewKeys.add(ProductCacheKeys.recentView(userId, productId));
            productViewRedisRepository.incrementView(userId, productId);
        }
    }

    private int viewCount(Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT views FROM product_view_stats WHERE product_id = ?",
                Integer.class, productId);
    }

    private long searchVersion(Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT search_version FROM products WHERE id = ?", Long.class, productId);
    }

    private String syncingCount(Long productId) {
        return redis.<String, String>opsForHash()
                .get(ProductCacheKeys.syncingViews(), productId.toString());
    }

    private void deleteSharedKeys() {
        redis.delete(List.of(
                ProductCacheKeys.pendingViews(),
                ProductCacheKeys.syncingViews()
        ));
    }
}
