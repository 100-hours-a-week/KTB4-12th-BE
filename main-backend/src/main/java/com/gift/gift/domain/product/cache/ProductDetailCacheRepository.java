package com.gift.gift.domain.product.cache;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Repository
public class ProductDetailCacheRepository {

    private static final DefaultRedisScript<Long> SAVE_SCRIPT =
            new DefaultRedisScript<>("""
                    local ttl = tonumber(ARGV[1])
                    if not ttl or ttl <= 0 then
                        return redis.error_reply('TTL must be positive')
                    end

                    redis.call('DEL', KEYS[1])

                    redis.call('HSET', KEYS[1],
                        'productId', ARGV[2],
                        'brandName', ARGV[3],
                        'productName', ARGV[4],
                        'unitPrice', ARGV[5],
                        'images', ARGV[6]
                    )

                    if ARGV[7] == '1' then
                        redis.call('HSET', KEYS[1], 'description', ARGV[8])
                    end

                    return redis.call('EXPIRE', KEYS[1], ttl)
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;
    private final long ttlSeconds;

    public ProductDetailCacheRepository(
            @Qualifier("productRedisTemplate")
            StringRedisTemplate redisTemplate,
            JsonMapper jsonMapper,
            @Value("${app.redis.product.detail-cache.ttl:2h}")
            Duration ttl
    ) {
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException(
                    "상품 캐시 TTL은 1초 이상이어야 합니다."
            );
        }

        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
        this.ttlSeconds = ttl.toSeconds();
    }

    public Optional<ProductDetailCache> findById(Long productId) {
        String key = ProductCacheKeys.detail(productId);

        Map<String, String> fields = redisTemplate
                .<String, String>opsForHash()
                .entries(key);

        if (fields.isEmpty()) {
            return Optional.empty();
        }

        try {
            Long cachedProductId = Long.valueOf(required(fields, "productId"));

            if (!productId.equals(cachedProductId)) {
                throw new IllegalArgumentException("캐시 상품 ID가 일치하지 않습니다.");
            }

            ProductDetailCache.ImageMetadata[] images = jsonMapper.readValue(
                    required(fields, "images"),
                    ProductDetailCache.ImageMetadata[].class
            );

            if (images == null || Arrays.stream(images).anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("캐시 이미지 목록이 올바르지 않습니다.");
            }

            ProductDetailCache cache = new ProductDetailCache(
                    cachedProductId,
                    required(fields, "brandName"),
                    required(fields, "productName"),
                    fields.get("description"),
                    new BigDecimal(required(fields, "unitPrice")),
                    List.copyOf(Arrays.asList(images))
            );

            return Optional.of(cache);
        } catch (JacksonException | IllegalArgumentException exception) {
            log.warn(
                    "상품 상세 캐시 형식 오류. productId={}",
                    productId,
                    exception
            );
            return Optional.empty();
        }
    }

    public void save(ProductDetailCache cache) {
        String key = ProductCacheKeys.detail(cache.productId());

        // Redis에 쓰기 전에 직렬화를 끝내 기존 캐시를 불필요하게 삭제하지 않는다.
        String imagesJson = jsonMapper.writeValueAsString(cache.images());

        Long result = redisTemplate.execute(
                SAVE_SCRIPT,
                List.of(key),
                Long.toString(ttlSeconds),
                cache.productId().toString(),
                cache.brandName(),
                cache.productName(),
                cache.unitPrice().toPlainString(),
                imagesJson,
                cache.description() == null ? "0" : "1",
                cache.description() == null ? "" : cache.description()
        );

        if (!Long.valueOf(1L).equals(result)) {
            throw new IllegalStateException("상품 캐시 저장 결과가 올바르지 않습니다.");
        }
    }

    public void deleteById(Long productId) {
        redisTemplate.delete(ProductCacheKeys.detail(productId));
    }

    private static String required(
            Map<String, String> fields,
            String name
    ) {
        String value = fields.get(name);

        if (value == null) {
            throw new IllegalArgumentException("캐시 필수 필드 누락: " + name);
        }

        return value;
    }
}
