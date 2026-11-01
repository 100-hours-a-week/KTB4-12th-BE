package com.gift.gift.domain.product.cache;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.gift.gift.domain.product.exception.ProductException;
import com.gift.gift.domain.product.repository.ProductDetailProjection;
import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.product.support.ImageUrlProvider;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductDetailCacheServiceTest {

    private ProductRepository products;
    private ProductDetailCacheRepository cache;
    private ImageUrlProvider imageUrls;
    private ProductDetailCacheService service;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        products = mock(ProductRepository.class);
        cache = mock(ProductDetailCacheRepository.class);
        imageUrls = mock(ImageUrlProvider.class);
        registry = new SimpleMeterRegistry();

        service = new ProductDetailCacheService(
                products,
                cache,
                imageUrls,
                new ProductDetailCacheMetrics(registry)
        );
    }

    @Test
    @DisplayName("캐시가 있으면 DB의 현재 재고와 캐시 정보로 상세를 반환하고 이미지 URL을 생성한다")
    void cacheHit_usesDatabaseStockAndGeneratesImageUrl() {
        when(products.findActiveStockQuantityById(399L))
                .thenReturn(Optional.of(0));
        when(cache.findById(399L)).thenReturn(Optional.of(
                new ProductDetailCache(
                        399L, "브랜드", "상품", null,
                        new BigDecimal("110000"),
                        List.of(new ProductDetailCache.ImageMetadata(
                                1L, "products/image.webp", 0
                        ))
                )
        ));
        when(imageUrls.generateUrl("products/image.webp"))
                .thenReturn("https://image.test/current");

        var response = service.getProductDetail(399L).product();

        assertThat(response.stockQuantity()).isZero();
        assertThat(response.description()).isNull();
        assertThat(response.unitPrice())
                .isEqualByComparingTo("110000");
        assertThat(response.images()).singleElement().satisfies(image ->
                assertThat(image.imageUrl())
                        .isEqualTo("https://image.test/current")
        );

        verify(products, never()).findActiveDetail(anyLong());
        verify(cache, never()).save(any());
        assertThat(registry.get("gift.product.detail.cache.requests")
                .tag("result", "hit").counter().count()).isEqualTo(1);
        assertThat(registry.get("gift.product.detail.stage")
                .tag("stage", "metadata_db").timer().count()).isZero();
    }

    @Test
    @DisplayName("캐시가 없으면 DB에서 상세를 조회하고 상품 메타데이터를 캐시에 저장한다")
    void cacheMiss_loadsDatabaseAndStoresMetadata() {
        when(products.findActiveStockQuantityById(399L))
                .thenReturn(Optional.of(7));
        when(cache.findById(399L)).thenReturn(Optional.empty());
        when(products.findActiveDetail(399L))
                .thenReturn(List.of(row()));

        var response = service.getProductDetail(399L).product();

        assertThat(response.stockQuantity()).isEqualTo(7);
        assertThat(response.images()).isEmpty();

        verify(cache).save(new ProductDetailCache(
                399L, "브랜드", "상품", null,
                new BigDecimal("110000"), List.of()
        ));
        assertThat(registry.get("gift.product.detail.cache.requests")
                .tag("result", "miss").counter().count()).isEqualTo(1);
        assertThat(registry.get("gift.product.detail.stage")
                .tag("stage", "metadata_db").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("삭제되었거나 존재하지 않는 상품이면 캐시를 조회하지 않고 예외를 발생시킨다")
    void deletedOrMissingProduct_doesNotReadCache() {
        when(products.findActiveStockQuantityById(399L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProductDetail(399L))
                .isInstanceOf(ProductException.class);

        verifyNoInteractions(cache, imageUrls);
    }

    @Test
    @DisplayName("Redis 조회가 실패하면 캐시 저장을 시도하지 않고 DB 조회 결과를 반환한다")
    void redisReadFailure_returnsDatabaseResultWithoutSavingAgain() {
        when(products.findActiveStockQuantityById(399L))
                .thenReturn(Optional.of(7));
        when(cache.findById(399L)).thenThrow(
                new DataAccessResourceFailureException("Redis unavailable")
        );
        when(products.findActiveDetail(399L))
                .thenReturn(List.of(row()));

        assertThat(service.getProductDetail(399L).product().productId())
                .isEqualTo(399L);

        verify(cache, never()).save(any());
        assertThat(registry.get("gift.product.detail.cache.requests")
                .tag("result", "error").counter().count()).isEqualTo(1);
        assertThat(registry.get("gift.product.detail.cache.requests")
                .tag("result", "miss").counter().count()).isZero();
        assertThat(registry.get("gift.product.detail.stage")
                .tag("stage", "cache_read").timer().count()).isEqualTo(1);
        assertThat(registry.get("gift.product.detail.stage")
                .tag("stage", "metadata_db").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Redis 저장이 실패해도 DB 조회 결과를 반환한다")
    void redisWriteFailure_doesNotDiscardDatabaseResult() {
        when(products.findActiveStockQuantityById(399L))
                .thenReturn(Optional.of(7));
        when(cache.findById(399L)).thenReturn(Optional.empty());
        when(products.findActiveDetail(399L))
                .thenReturn(List.of(row()));
        doThrow(new DataAccessResourceFailureException("Redis unavailable"))
                .when(cache).save(any());

        assertThat(service.getProductDetail(399L).product().stockQuantity())
                .isEqualTo(7);
        assertThat(registry.get("gift.product.detail.cache.writes")
                .tag("result", "error").counter().count()).isEqualTo(1);
        assertThat(registry.get("gift.product.detail.cache.writes")
                .tag("result", "success").counter().count()).isZero();
        assertThat(registry.get("gift.product.detail.stage")
                .tag("stage", "cache_write").timer().count()).isEqualTo(1);
    }

    private ProductDetailProjection row() {
        return new ProductDetailProjection(
                399L,
                "브랜드",
                "상품",
                null,
                new BigDecimal("110000"),
                7,
                null,
                null,
                null
        );
    }
}
