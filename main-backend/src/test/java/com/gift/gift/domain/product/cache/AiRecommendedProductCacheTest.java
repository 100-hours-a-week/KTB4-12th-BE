package com.gift.gift.domain.product.cache;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AiRecommendedProductCacheTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
    }

    private AiRecommendedProductCache cache() {
        return new AiRecommendedProductCache(redis, JsonMapper.builder().build(), Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("추천 목록 캐시 적중은 DB 로더 없이 상품 ID를 반환한다")
    void returnsCachedRecommendationIdsWithoutLoading() {
        when(values.get("gift:product:ai-recommended-version:1")).thenReturn("7");
        when(values.get("gift:product:ai-recommended-list:1:7")).thenReturn("[3,2,1]");
        var cache = cache();

        cache.resolveAnalyzedSourceVersion(1L, () -> {
            throw new AssertionError("Unexpected DB read");
        });
        var lookup = cache.getOrLoadProductIds(1L, 7L, () -> {
            throw new AssertionError("Unexpected loader");
        });

        assertThat(lookup.productIds()).containsExactly(3L, 2L, 1L);
    }

    @Test
    @DisplayName("캐시가 비어 있으면 추천 버전과 ID 목록을 DB에서 읽어 Redis에 적재한다")
    void loadsBothColdKeys() {
        when(values.setIfAbsent(
                "gift:product:ai-recommended-version:1",
                "7",
                Duration.ofMinutes(30)
        )).thenReturn(true);
        var cache = cache();

        assertThat(cache.resolveAnalyzedSourceVersion(1L, () -> 7L).version()).isEqualTo(7L);
        assertThat(cache.getOrLoadProductIds(1L, 7L, () -> List.of(3L, 2L, 1L)).productIds())
                .containsExactly(3L, 2L, 1L);

        verify(values).set("gift:product:ai-recommended-list:1:7", "[3,2,1]", Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("추천 ID 캐시 조회에 실패하면 DB 경로 사용을 요청한다")
    void fallsBackWhenRecommendationIdsCannotBeReadFromRedis() {
        when(values.get(anyString())).thenThrow(new DataAccessResourceFailureException("test"));
        var lookup = cache().getOrLoadProductIds(1L, 7L, () -> {
            throw new AssertionError("The caller should use the legacy DB path");
        });

        assertThat(lookup.usable()).isFalse();
    }
}
