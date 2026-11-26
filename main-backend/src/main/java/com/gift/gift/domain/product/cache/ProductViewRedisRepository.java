package com.gift.gift.domain.product.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class ProductViewRedisRepository {

    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) then
                        redis.call('HINCRBY', KEYS[2], ARGV[2], 1)
                        return 1
                    end
                    return 0
                    """, Long.class);

    private static final DefaultRedisScript<Long> MOVE_TO_SYNCING_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('EXISTS', KEYS[2]) == 1 then
                        return 1
                    end
                    if redis.call('EXISTS', KEYS[1]) == 0 then
                        return 0
                    end
                    redis.call('RENAME', KEYS[1], KEYS[2])
                    return 1
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final long recentViewSeconds;

    public ProductViewRedisRepository(
            @Qualifier("productRedisTemplate")
            StringRedisTemplate redisTemplate,
            @Value("${app.redis.product.recent-view.ttl:24h}")
            Duration recentViewTtl
    ) {
        if (recentViewTtl == null || recentViewTtl.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException(
                    "최근 조회 유지 시간은 1초 이상이어야 합니다."
            );
        }

        this.redisTemplate = redisTemplate;
        this.recentViewSeconds = recentViewTtl.toSeconds();
    }

    public boolean incrementView(Long userId, Long productId) {
        Long result = redisTemplate.execute(
                INCREMENT_SCRIPT,
                List.of(
                        ProductCacheKeys.recentView(userId, productId),
                        ProductCacheKeys.pendingViews()
                ),
                Long.toString(recentViewSeconds),
                productId.toString()
        );

        return toBoolean(result, "조회수 증가 결과가 올바르지 않습니다.");
    }

    public boolean moveToSyncing() {
        Long result = redisTemplate.execute(
                MOVE_TO_SYNCING_SCRIPT,
                List.of(
                        ProductCacheKeys.pendingViews(),
                        ProductCacheKeys.syncingViews()
                )
        );

        return toBoolean(result, "조회수 반영 대상 이동 결과가 올바르지 않습니다.");
    }

    public Map<String, String> findSyncing() {
        return redisTemplate
                .<String, String>opsForHash()
                .entries(ProductCacheKeys.syncingViews());
    }

    public void deleteSyncing(Collection<String> productIds) {
        if (productIds.isEmpty()) {
            return;
        }

        redisTemplate.opsForHash().delete(
                ProductCacheKeys.syncingViews(),
                productIds.toArray()
        );
    }

    public long countPending() {
        Long size = redisTemplate
                .opsForHash()
                .size(ProductCacheKeys.pendingViews());

        return size == null ? 0L : size;
    }

    private boolean toBoolean(Long result, String errorMessage) {
        if (result == null || (result != 0L && result != 1L)) {
            throw new IllegalStateException(errorMessage);
        }

        return result == 1L;
    }
}
