package com.gift.gift.domain.product.cache;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.domain.recommendation.profiling.event.RecommendationAnalysisCompletedEvent;

@Slf4j
@Component
public class AiRecommendedProductCache {

    private static final String PRODUCT_KEY_PREFIX = "gift:product:ai-recommended-list:";
    private static final String ACTIVE_VERSION_KEY_PREFIX = "gift:product:ai-recommended-version:";
    private static final int MAX_RECOMMENDED_PRODUCTS = 30;
    private static final TypeReference<List<Long>> PRODUCT_IDS_TYPE = new TypeReference<>() { };

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;
    private final Duration ttl;

    public AiRecommendedProductCache(
            @Qualifier("productRedisTemplate") StringRedisTemplate redisTemplate,
            JsonMapper jsonMapper,
            @Value("${app.redis.product.ai-recommended-cache.ttl:30m}") Duration ttl
    ) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(
                    "추천 상품 캐시 TTL은 0보다 커야 한다."
            );
        }

        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
        this.ttl = ttl;
    }

    public VersionLookup resolveAnalyzedSourceVersion(Long recipientId, LongSupplier databaseVersionLoader) {

        String key = activeVersionKey(recipientId);
        String cachedVersionValue;

        try {
            cachedVersionValue = redisTemplate.opsForValue().get(key);
        } catch (DataAccessException exception) {
            log.warn(
                    "추천 분석 버전 캐시 조회에 실패했습니다. recipientId={}",
                    recipientId,
                    exception
            );

            return new VersionLookup(databaseVersionLoader.getAsLong(), false);
        }

        OptionalLong cachedVersion = parseVersion(cachedVersionValue);
        if (cachedVersion.isPresent()) {
            return new VersionLookup(cachedVersion.getAsLong(), true);
        }

        if (cachedVersionValue != null) {
            try {
                redisTemplate.delete(key);
            } catch (DataAccessException exception) {
                log.warn(
                        "잘못된 추천 분석 버전 캐시를 제거하지 못했다. " + "recipientId={}",
                        recipientId,
                        exception
                );

                return new VersionLookup(databaseVersionLoader.getAsLong(), false);
            }
        }

        long databaseVersion = databaseVersionLoader.getAsLong();

        try {
            Boolean initialized = redisTemplate.opsForValue().setIfAbsent(
                    key,
                    Long.toString(databaseVersion),
                    ttl
            );

            if (Boolean.TRUE.equals(initialized)) {
                return new VersionLookup(databaseVersion, true);
            }

            OptionalLong concurrentVersion = parseVersion(
                    redisTemplate.opsForValue().get(key)
            );

            return new VersionLookup(
                    concurrentVersion.orElse(databaseVersion),
                    true
            );
        } catch (DataAccessException exception) {
            log.warn(
                    "추천 분석 버전 캐시 초기화에 실패했다. recipientId={}",
                    recipientId,
                    exception
            );
            return new VersionLookup(databaseVersion, false);
        }
    }

    public ProductIdsLookup getOrLoadProductIds(
            Long recipientId,
            long analyzedSourceVersion,
            Supplier<List<Long>> databaseLoader
    ) {
        String key = productListKey(recipientId, analyzedSourceVersion);
        String cachedValue;

        try {
            cachedValue = redisTemplate.opsForValue().get(key);
        } catch (DataAccessException exception) {
            log.warn(
                    "추천 상품 캐시 조회에 실패했다. recipientId={}",
                    recipientId,
                    exception
            );
            return new ProductIdsLookup(List.of(), false);
        }

        if (cachedValue != null) {
            try {
                List<Long> productIds = jsonMapper.readValue(cachedValue, PRODUCT_IDS_TYPE);
                return new ProductIdsLookup(validateProductIds(productIds), true);
            } catch (JacksonException | IllegalArgumentException exception) {
                log.warn(
                        "추천 상품 캐시를 읽을 수 없어 DB에서 다시 조회한다. " + "recipientId={}",
                        recipientId,
                        exception
                );
            }
        }

        List<Long> productIds = validateProductIds(databaseLoader.get());

        try {
            redisTemplate.opsForValue().set(
                    key,
                    jsonMapper.writeValueAsString(productIds),
                    ttl
            );
        } catch (JacksonException | DataAccessException exception) {
            log.warn(
                    "추천 상품 캐시 저장에 실패했다. recipientId={}",
                    recipientId,
                    exception
            );
        }

        return new ProductIdsLookup(productIds, true);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecommendationAnalysisCompleted(RecommendationAnalysisCompletedEvent event) {

        String key = activeVersionKey(event.recipientId());

        try {
            redisTemplate.opsForValue().set(
                    key,
                    Long.toString(event.analyzedSourceVersion()),
                    ttl
            );
        } catch (DataAccessException exception) {
            log.warn(
                    "커밋된 추천 분석 버전을 Redis에 반영하지 못했다. " + "recipientId={}",
                    event.recipientId(),
                    exception
            );

            try {
                redisTemplate.delete(key);
            } catch (DataAccessException deleteException) {
                log.warn(
                        "추천 분석 버전 캐시 제거에도 실패했다. recipientId={}",
                        event.recipientId(),
                        deleteException
                );
            }
        }
    }

    String productListKey(Long recipientId, long analyzedSourceVersion) {
        return PRODUCT_KEY_PREFIX + recipientId + ":" + analyzedSourceVersion;
    }

    private String activeVersionKey(Long recipientId) {
        return ACTIVE_VERSION_KEY_PREFIX + recipientId;
    }

    private OptionalLong parseVersion(String value) {
        if (value == null) {
            return OptionalLong.empty();
        }

        try {
            long version = Long.parseLong(value);
            return version < 0
                    ? OptionalLong.empty()
                    : OptionalLong.of(version);
        } catch (NumberFormatException exception) {
            return OptionalLong.empty();
        }
    }

    private List<Long> validateProductIds(List<Long> productIds) {
        if (productIds == null
                || productIds.size() > MAX_RECOMMENDED_PRODUCTS
                || productIds.stream().anyMatch(
                id -> id == null || id <= 0
        )
                || productIds.stream().distinct().count() != productIds.size()) {
            throw new IllegalArgumentException(
                    "추천 상품 ID 캐시 값이 올바르지 않다."
            );
        }

        return List.copyOf(productIds);
    }

    public record VersionLookup(long version, boolean redisAvailable) {
    }

    public record ProductIdsLookup(List<Long> productIds, boolean usable) {

        public ProductIdsLookup {
            productIds = List.copyOf(productIds);
        }
    }
}
