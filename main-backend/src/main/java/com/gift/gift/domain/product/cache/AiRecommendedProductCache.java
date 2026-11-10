package com.gift.gift.domain.product.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
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
import com.gift.gift.domain.friend.event.FriendshipChangedEvent;
import com.gift.gift.domain.product.dto.response.ProductListResponse;
import com.gift.gift.domain.product.repository.ProductSearchCondition;
import com.gift.gift.domain.product.repository.ProductSort;
import com.gift.gift.domain.product.query.ProductPageAssembler;

@Slf4j
@Component
public class AiRecommendedProductCache {

    private static final String PRODUCT_KEY_PREFIX = "gift:product:ai-recommended-list:";
    private static final String ACTIVE_VERSION_KEY_PREFIX = "gift:product:ai-recommended-version:";
    private static final String RESPONSE_KEY_PREFIX = "gift:product:ai-recommended-response:";
    private static final String RECIPIENT_ACTIVE_KEY_PREFIX = "gift:product:recipient-active:";
    private static final String FRIENDSHIP_KEY_PREFIX = "gift:product:recipient-friend:";
    private static final int MAX_RECOMMENDED_PRODUCTS = 30;
    private static final Duration RESPONSE_TTL = Duration.ofMinutes(30);
    // shortcut: 계정 비활성화 변경 경로가 없어 5초 TTL로만 오래된 권한을 제한한다.
    private static final Duration ACCESS_TTL = Duration.ofSeconds(5);
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
                List<Long> validated = validateProductIds(productIds);
                return new ProductIdsLookup(validated, true);
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

    public Optional<ProductListResponse> findCachedResponse(
            ProductSearchCondition condition,
            String cursor
    ) {
        String key = responseKey(condition, cursor);
        try {
            String cachedValue = redisTemplate.opsForValue().get(key);
            if (cachedValue == null) {
                return Optional.empty();
            }

            ProductListResponse response = jsonMapper.readValue(
                    cachedValue,
                    ProductListResponse.class
            );
            if (response == null || response.appliedSort() == null
                    || response.products() == null || response.pagination() == null) {
                throw new IllegalArgumentException("추천 응답 캐시 구조가 올바르지 않습니다.");
            }
            if ((response.appliedSort() != ProductSort.AI_RECOMMENDED
                    && response.appliedSort() != ProductSort.POPULAR)
                    || response.products().size() > ProductPageAssembler.PAGE_SIZE
                    || response.products().stream().anyMatch(product ->
                    product.productId() == null || product.productId() <= 0)
                    || response.pagination().hasNext()
                    != (response.pagination().nextCursor() != null)) {
                throw new IllegalArgumentException("추천 응답 캐시 값이 올바르지 않습니다.");
            }
            return Optional.of(response);
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            try {
                redisTemplate.delete(key);
            } catch (DataAccessException deleteException) {
                log.warn("손상된 추천 응답 캐시를 제거하지 못했습니다.", deleteException);
            }
            log.warn("추천 응답 캐시를 읽을 수 없어 DB에서 다시 조회합니다.", exception);
            return Optional.empty();
        } catch (DataAccessException exception) {
            log.warn("추천 응답 캐시 조회에 실패했습니다.", exception);
            return Optional.empty();
        }
    }

    public void cacheResponse(
            ProductSearchCondition condition,
            String cursor,
            ProductListResponse response
    ) {
        try {
            redisTemplate.opsForValue().set(
                    responseKey(condition, cursor),
                    jsonMapper.writeValueAsString(response),
                    RESPONSE_TTL
            );
        } catch (JacksonException | DataAccessException exception) {
            log.warn("추천 응답 캐시 저장에 실패했습니다.", exception);
        }
    }

    public boolean isRecipientActive(Long recipientId, BooleanSupplier databaseLoader) {
        return getOrLoadAccess(
                RECIPIENT_ACTIVE_KEY_PREFIX + recipientId,
                databaseLoader
        );
    }

    public boolean areFriends(
            Long userId,
            Long friendUserId,
            BooleanSupplier databaseLoader
    ) {
        long first = Math.min(userId, friendUserId);
        long second = Math.max(userId, friendUserId);
        return getOrLoadAccess(
                FRIENDSHIP_KEY_PREFIX + first + ":" + second,
                databaseLoader
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecommendationAnalysisCompleted(RecommendationAnalysisCompletedEvent event) {
        String key = activeVersionKey(event.recipientId());

        try {
            // 커밋 후 이벤트 순서가 뒤집혀도 과거 버전으로 덮어쓰지 않는다.
            redisTemplate.delete(key);
        } catch (DataAccessException exception) {
            log.warn(
                    "커밋된 추천 분석 버전 캐시를 무효화하지 못했다. recipientId={}",
                    event.recipientId(),
                    exception
            );
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFriendshipChanged(FriendshipChangedEvent event) {
        long first = Math.min(event.userId(), event.friendUserId());
        long second = Math.max(event.userId(), event.friendUserId());
        try {
            redisTemplate.delete(FRIENDSHIP_KEY_PREFIX + first + ":" + second);
        } catch (DataAccessException exception) {
            log.warn("친구 관계 캐시 무효화에 실패했습니다.", exception);
        }
    }

    String productListKey(Long recipientId, long analyzedSourceVersion) {
        return PRODUCT_KEY_PREFIX + recipientId + ":" + analyzedSourceVersion;
    }

    private String activeVersionKey(Long recipientId) {
        return ACTIVE_VERSION_KEY_PREFIX + recipientId;
    }

    private boolean getOrLoadAccess(
            String key,
            BooleanSupplier databaseLoader
    ) {
        try {
            String cachedValue = redisTemplate.opsForValue().get(key);
            if ("1".equals(cachedValue)) {
                return true;
            }
            if ("0".equals(cachedValue)) {
                return false;
            }
            if (cachedValue != null) {
                redisTemplate.delete(key);
            }
        } catch (DataAccessException exception) {
            log.warn("수신인 접근 캐시 조회에 실패해 DB를 조회합니다.", exception);
            return databaseLoader.getAsBoolean();
        }

        boolean value = databaseLoader.getAsBoolean();
        try {
            redisTemplate.opsForValue().set(key, value ? "1" : "0", ACCESS_TTL);
        } catch (DataAccessException exception) {
            log.warn("수신인 접근 캐시 저장에 실패했습니다.", exception);
        }
        return value;
    }

    private String responseKey(ProductSearchCondition condition, String cursor) {
        String query = condition.query();
        String categories = String.join(",", condition.categoryIds().stream()
                .map(String::valueOf)
                .toList());
        return RESPONSE_KEY_PREFIX
                + condition.recipientUserId() + ":"
                + condition.analyzedSourceVersion() + ":"
                + condition.requestedSort().name() + ":"
                + digest(query) + ":"
                + digest(categories) + ":"
                + digest(cursor == null ? "null:" : "value:" + cursor);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
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
