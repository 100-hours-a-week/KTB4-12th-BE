package com.gift.gift.domain.product.cache;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProductDetailCacheRepositoryTest {

    @Test
    @DisplayName("상품 상세 캐시 조회 시 가격을 복원하고 누락된 설명은 null, 빈 이미지 목록은 빈 목록으로 반환한다")
    void findById_restoresPriceAndNullableDescription() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        HashOperations<String, String, String> hashes =
                mock(HashOperations.class);

        when(redis.<String, String>opsForHash()).thenReturn(hashes);
        when(hashes.entries("product:detail:v1:399")).thenReturn(Map.of(
                "productId", "399",
                "brandName", "브랜드",
                "productName", "상품명",
                "unitPrice", "110000",
                "images", "[]"
        ));

        ProductDetailCacheRepository repository =
                new ProductDetailCacheRepository(
                        redis,
                        JsonMapper.builder().build(),
                        Duration.ofHours(2)
                );

        ProductDetailCache cache = repository.findById(399L).orElseThrow();

        assertThat(cache.productId()).isEqualTo(399L);
        assertThat(cache.unitPrice())
                .isEqualByComparingTo(new BigDecimal("110000"));
        assertThat(cache.description()).isNull();
        assertThat(cache.images()).isEmpty();
    }

    @Test
    @DisplayName("상품 상세 캐시에 필수 필드가 누락되면 캐시 미스로 처리한다")
    void findById_treatsIncompleteHashAsMiss() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        HashOperations<String, String, String> hashes =
                mock(HashOperations.class);

        when(redis.<String, String>opsForHash()).thenReturn(hashes);
        when(hashes.entries("product:detail:v1:399"))
                .thenReturn(Map.of("productId", "399"));

        ProductDetailCacheRepository repository =
                new ProductDetailCacheRepository(
                        redis,
                        JsonMapper.builder().build(),
                        Duration.ofHours(2)
                );

        assertThat(repository.findById(399L)).isEmpty();
    }
}
