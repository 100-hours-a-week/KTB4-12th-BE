package com.gift.gift.domain.product.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductViewRedisRepositoryIntegrationTest {

    private static final long FIRST_PRODUCT_ID = 9_100_001L;
    private static final long SECOND_PRODUCT_ID = 9_100_002L;

    @Autowired
    private ProductViewRedisRepository repository;

    @Autowired
    @Qualifier("productRedisTemplate")
    private StringRedisTemplate redis;

    private final List<String> recentViewKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        deleteSharedKeys();
    }

    @AfterEach
    void tearDown() {
        deleteSharedKeys();
        if (!recentViewKeys.isEmpty()) {
            redis.delete(recentViewKeys);
        }
        recentViewKeys.clear();
    }

    @Test
    @DisplayName("최초 조회만 조회수를 올리고 최근 조회 키에 24시간 만료를 설정한다")
    void incrementView_incrementsOnce() {
        long userId = randomUserId();

        assertThat(increment(userId, FIRST_PRODUCT_ID)).isTrue();
        assertThat(increment(userId, FIRST_PRODUCT_ID)).isFalse();

        assertThat(pendingCount(FIRST_PRODUCT_ID)).isEqualTo("1");
        assertThat(redis.getExpire(ProductCacheKeys.recentView(userId, FIRST_PRODUCT_ID)))
                .isBetween(86_000L, 86_400L);
    }

    @Test
    @DisplayName("서로 다른 사용자의 조회는 모두 올리고 상품별로 분리한다")
    void incrementView_countsEachUser() {
        increment(randomUserId(), FIRST_PRODUCT_ID);
        increment(randomUserId(), FIRST_PRODUCT_ID);
        increment(randomUserId(), FIRST_PRODUCT_ID);
        increment(randomUserId(), SECOND_PRODUCT_ID);

        assertThat(pendingCount(FIRST_PRODUCT_ID)).isEqualTo("3");
        assertThat(pendingCount(SECOND_PRODUCT_ID)).isEqualTo("1");
    }

    @Test
    @DisplayName("같은 사용자의 동시 최초 조회는 조회수를 한 번만 올린다")
    void incrementView_incrementsOnceUnderConcurrency() throws Exception {
        long userId = randomUserId();
        recentViewKeys.add(ProductCacheKeys.recentView(userId, FIRST_PRODUCT_ID));
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            calls.add(() -> repository.incrementView(userId, FIRST_PRODUCT_ID));
        }

        int incremented = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(5)) {
            for (Future<Boolean> result : executor.invokeAll(calls, 30, TimeUnit.SECONDS)) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    incremented++;
                }
            }
        }

        assertThat(incremented).isEqualTo(1);
        assertThat(pendingCount(FIRST_PRODUCT_ID)).isEqualTo("1");
    }

    @Test
    @DisplayName("pending를 syncing으로 옮기고 이후 조회는 새 pending에 쌓는다")
    void moveToSyncing_movesPendingToSyncing() {
        assertThat(repository.moveToSyncing()).isFalse();

        increment(randomUserId(), FIRST_PRODUCT_ID);
        assertThat(repository.moveToSyncing()).isTrue();

        assertThat(redis.hasKey(ProductCacheKeys.pendingViews())).isFalse();
        assertThat(repository.findSyncing()).containsEntry(String.valueOf(FIRST_PRODUCT_ID), "1");

        increment(randomUserId(), SECOND_PRODUCT_ID);
        assertThat(pendingCount(SECOND_PRODUCT_ID)).isEqualTo("1");
        assertThat(repository.findSyncing()).doesNotContainKey(String.valueOf(SECOND_PRODUCT_ID));
    }

    @Test
    @DisplayName("syncing이 남아 있으면 pending을 덮어쓰지 않고 삭제한 뒤에만 옮긴다")
    void moveToSyncing_keepsExistingSyncing() {
        increment(randomUserId(), FIRST_PRODUCT_ID);
        repository.moveToSyncing();
        increment(randomUserId(), SECOND_PRODUCT_ID);

        assertThat(repository.moveToSyncing()).isTrue();
        assertThat(repository.findSyncing()).containsOnlyKeys(String.valueOf(FIRST_PRODUCT_ID));
        assertThat(pendingCount(SECOND_PRODUCT_ID)).isEqualTo("1");

        repository.deleteSyncing(List.of(String.valueOf(FIRST_PRODUCT_ID)));
        assertThat(redis.hasKey(ProductCacheKeys.syncingViews())).isFalse();

        assertThat(repository.moveToSyncing()).isTrue();
        assertThat(repository.findSyncing()).containsOnlyKeys(String.valueOf(SECOND_PRODUCT_ID));
    }

    @Test
    @DisplayName("pending에 조회수가 있는 상품 수를 반환한다")
    void countPending_returnsProductCount() {
        assertThat(repository.countPending()).isZero();

        increment(randomUserId(), FIRST_PRODUCT_ID);
        increment(randomUserId(), FIRST_PRODUCT_ID);
        increment(randomUserId(), SECOND_PRODUCT_ID);

        assertThat(repository.countPending()).isEqualTo(2);

        repository.moveToSyncing();
        assertThat(repository.countPending()).isZero();
    }

    private boolean increment(long userId, long productId) {
        recentViewKeys.add(ProductCacheKeys.recentView(userId, productId));
        return repository.incrementView(userId, productId);
    }

    private String pendingCount(long productId) {
        return redis.<String, String>opsForHash()
                .get(ProductCacheKeys.pendingViews(), String.valueOf(productId));
    }

    private long randomUserId() {
        return ThreadLocalRandom.current().nextLong(1_000_000L, 1_000_000_000_000L);
    }

    private void deleteSharedKeys() {
        redis.delete(List.of(
                ProductCacheKeys.pendingViews(),
                ProductCacheKeys.syncingViews()
        ));
    }
}
