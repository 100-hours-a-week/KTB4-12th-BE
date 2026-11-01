package com.gift.gift.domain.product.cache;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.product.event.ProductMetadataChangedEvent;
import com.gift.gift.domain.product.exception.ProductException;
import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.product.service.ProductDetailService;
import com.gift.gift.domain.product.support.ImageUrlProvider;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.gift.gift.support.RedisTestContainerInitializer.REDIS;

@SpringBootTest(properties = {
        "spring.jpa.open-in-view=false",
        "app.redis.product.detail-cache.ttl=2h"
})
class ProductDetailCacheIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductDetailCacheService cacheService;
    @Autowired private ProductDetailService detailService;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired @Qualifier("productRedisTemplate")
    private StringRedisTemplate redis;
    @MockitoSpyBean private ProductDetailCacheRepository cache;
    @MockitoSpyBean private ProductRepository products;
    @MockitoBean private ImageUrlProvider imageUrls;

    private Long categoryId;
    private Long productId;
    private Long userId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO categories (name, created_at, updated_at)
                VALUES (?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, suffix);
        categoryId = jdbc.queryForObject(
                "SELECT id FROM categories WHERE name = ?", Long.class, suffix);
        jdbc.update("""
                INSERT INTO products
                (category_id, name, brand, description, price, quantity, views, sales,
                 created_at, updated_at)
                VALUES (?, ?, '브랜드', NULL, 12345, 7, 0, 0,
                        CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, categoryId, suffix);
        productId = jdbc.queryForObject(
                "SELECT id FROM products WHERE name = ?", Long.class, suffix);
        jdbc.update("""
                INSERT INTO users
                (email, password, name, birth, status, is_birthday_public, is_first_login,
                 created_at, updated_at)
                VALUES (?, 'unused-test-password', '캐시검증', '2000-01-01', 'ACTIVE',
                        FALSE, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, suffix + "@cache.test");
        userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, suffix + "@cache.test");
        when(imageUrls.generateUrl(anyString()))
                .thenAnswer(call -> "https://image.test/" + call.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        reset(cache, products);
        if (productId != null) {
            redis.delete(ProductCacheKeys.detail(productId));
            jdbc.update("DELETE FROM product_view_events WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM product_views WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM product_images WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM products WHERE id = ?", productId);
        }
        if (userId != null) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        if (categoryId != null) {
            jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        }
    }

    @Test
    @DisplayName("실제 Redis 미스는 DB에서 채우고 히트는 메타데이터 조회 없이 TTL을 유지한다")
    void missThenHit_preservesMetadataAndTtl() {
        var first = cacheService.getProductDetail(productId).product();
        assertThat(first.unitPrice()).isEqualByComparingTo("12345");
        assertThat(first.description()).isNull();
        assertThat(first.images()).isEmpty();
        assertThat(redis.type(ProductCacheKeys.detail(productId)).code()).isEqualTo("hash");
        assertThat(redis.getExpire(ProductCacheKeys.detail(productId)))
                .isBetween(7100L, 7200L);

        clearInvocations(products);
        assertThat(cacheService.getProductDetail(productId).product()).isEqualTo(first);
        verify(products, never()).findActiveDetail(anyLong());
        verify(products).findActiveStockQuantityById(productId);
    }

    @Test
    @DisplayName("캐시 적중 중에도 변경한 재고를 읽고 삭제된 상품을 차단한다")
    void warmCache_readsNewStockAndRejectsDeletedProduct() {
        cacheService.getProductDetail(productId);
        jdbc.update("UPDATE products SET quantity = 0 WHERE id = ?", productId);
        assertThat(cacheService.getProductDetail(productId).product().stockQuantity()).isZero();

        jdbc.update("UPDATE products SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", productId);
        clearInvocations(cache);
        assertThatThrownBy(() -> cacheService.getProductDetail(productId))
                .isInstanceOf(ProductException.class);
        verify(cache, never()).findById(anyLong());
    }

    @Test
    @DisplayName("실제 Hash 교체는 이전 설명을 제거하고 소수 가격과 이미지 순서를 보존한다")
    void hashReplacement_preservesPriceAndImageOrder() {
        cache.save(new ProductDetailCache(productId, "브랜드", "이전", "설명",
                BigDecimal.ONE, List.of()));
        var expected = new ProductDetailCache(productId, "브랜드", "변경", null,
                new BigDecimal("12345.67"), List.of(
                        new ProductDetailCache.ImageMetadata(11L, "first.webp", 0),
                        new ProductDetailCache.ImageMetadata(12L, "second.webp", 1)));
        cache.save(expected);

        assertThat(cache.findById(productId)).contains(expected);
        assertThat(redis.opsForHash().hasKey(ProductCacheKeys.detail(productId), "description"))
                .isFalse();
        assertThat(cacheService.getProductDetail(productId).product().images())
                .extracting(image -> image.imageUrl())
                .containsExactly("https://image.test/first.webp", "https://image.test/second.webp");
    }

    @Test
    @DisplayName("DB 이미지 순서를 캐시에 보존하고 이미지 변경 커밋 후 새 URL을 반환한다")
    void imageChangeEvent_reloadsOrderedImageMetadata() {
        for (int order : List.of(1, 0)) {
            jdbc.update("""
                    INSERT INTO product_images
                    (product_id, object_key, sort_order, created_at, updated_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """, productId, "cache-test/" + productId + "/" + order, order);
        }
        var first = cacheService.getProductDetail(productId).product();
        assertThat(first.images()).extracting(image -> image.displayOrder())
                .containsExactly(0, 1);
        assertThat(cacheService.getProductDetail(productId).product()).isEqualTo(first);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE product_images SET object_key = ?
                    WHERE product_id = ? AND sort_order = 0
                    """, "cache-test/updated", productId);
            events.publishEvent(new ProductMetadataChangedEvent(productId));
        });
        assertThat(cache.findById(productId)).isEmpty();
        assertThat(cacheService.getProductDetail(productId).product().images().getFirst().imageUrl())
                .isEqualTo("https://image.test/cache-test/updated");
    }

    @Test
    @DisplayName("캐시 히트에서도 최초 이벤트만 저장하고 24시간 경과 후 다시 저장한다")
    void warmCache_preservesViewCooldownAndEvents() {
        cacheService.getProductDetail(productId);
        detailService.getProductDetail(userId, productId);
        detailService.getProductDetail(userId, productId);
        assertThat(count("product_views")).isEqualTo(1);
        assertThat(count("product_view_events")).isEqualTo(1);

        jdbc.update("""
                UPDATE product_views
                SET last_viewed_at = CURRENT_TIMESTAMP(6) - INTERVAL 25 HOUR
                WHERE user_id = ? AND product_id = ?
                """, userId, productId);
        detailService.getProductDetail(userId, productId);
        assertThat(count("product_views")).isEqualTo(1);
        assertThat(count("product_view_events")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT views FROM product_view_stats WHERE product_id = ?",
                Integer.class, productId)).isZero();
    }

    @Test
    @DisplayName("실제 DB 트랜잭션 커밋 후 캐시를 삭제하고 다음 조회가 변경 가격을 적재한다")
    void committedChangeEvent_evictsAndReloadsNewPrice() {
        cacheService.getProductDetail(productId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("UPDATE products SET price = 20000 WHERE id = ?", productId);
            // 실제 수정 API가 아닌 이벤트 계약 자체를 검증한다.
            events.publishEvent(new ProductMetadataChangedEvent(productId));
            assertThat(cache.findById(productId)).isPresent();
        });
        assertThat(cache.findById(productId)).isEmpty();
        assertThat(cacheService.getProductDetail(productId).product().unitPrice())
                .isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("변경 이벤트를 발행해도 DB 롤백이면 기존 캐시와 가격을 유지한다")
    void rolledBackChangeEvent_keepsCacheAndDatabasePrice() {
        cacheService.getProductDetail(productId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("UPDATE products SET price = 20000 WHERE id = ?", productId);
            events.publishEvent(new ProductMetadataChangedEvent(productId));
            status.setRollbackOnly();
        });
        assertThat(cache.findById(productId)).isPresent();
        assertThat(jdbc.queryForObject("SELECT price FROM products WHERE id = ?",
                BigDecimal.class, productId)).isEqualByComparingTo("12345");
    }

    @Test
    @DisplayName("Redis 읽기 실패는 실제 DB 결과를 반환하고 재저장하지 않는다")
    void redisReadFailure_fallsBackToRealDatabase() {
        doThrow(new DataAccessResourceFailureException("injected read failure"))
                .when(cache).findById(productId);
        assertThat(cacheService.getProductDetail(productId).product().unitPrice())
                .isEqualByComparingTo("12345");
        verify(cache, never()).save(any());
    }

    @Test
    @DisplayName("Redis 쓰기 실패에도 실제 DB 상세 결과를 반환한다")
    void redisWriteFailure_returnsRealDatabaseResult() {
        doThrow(new DataAccessResourceFailureException("injected write failure"))
                .when(cache).save(any());
        assertThat(cacheService.getProductDetail(productId).product().stockQuantity()).isEqualTo(7);
        assertThat(redis.hasKey(ProductCacheKeys.detail(productId))).isFalse();
    }

    @Test
    @DisplayName("만료된 실제 Redis 키는 미스로 처리해 DB에서 다시 적재한다")
    void expiredKey_reloadsDatabase() {
        cacheService.getProductDetail(productId);
        redis.expire(ProductCacheKeys.detail(productId), Duration.ZERO);
        assertThat(cache.findById(productId)).isEmpty();
        clearInvocations(products);

        cacheService.getProductDetail(productId);

        verify(products).findActiveDetail(productId);
        assertThat(cache.findById(productId)).isPresent();
    }

    @Test
    @Timeout(20)
    @DisplayName("테스트 Redis가 응답하지 않으면 명령 제한시간 이후 실제 DB로 대체한다")
    void unresponsiveRedis_fallsBackAfterCommandTimeout() {
        cacheService.getProductDetail(productId);
        clearInvocations(cache);
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertThat(cacheService.getProductDetail(productId).product().unitPrice())
                    .isEqualByComparingTo("12345");
            verify(cache, never()).save(any());
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE product_id = ?",
                Integer.class, productId);
    }
}
