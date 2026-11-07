package com.gift.gift.domain.recommendation.profiling.repository;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.recommendation.profiling.entity.ProfilingResultStatus;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "app.scheduling.enabled=false",
        "app.ai-profile.cleanup-enabled=false",
        "app.ai-profile.amqp.enabled=false",
        "app.outbox.enabled=false"
})
class ProfilingJobCleanupStoreIntegrationTest {

    // 다른 테스트의 최근 이력과 섞이지 않는 고정 기준 시각.
    private static final LocalDateTime NOW =
            LocalDateTime.of(2000, 2, 1, 3, 30);

    @Autowired ProfilingJobCleanupStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private Long userId;
    private Long profileId;
    private Long categoryId;
    private Long productId;
    private long version;

    @BeforeEach
    void setUp() {
        String key = UUID.randomUUID().toString();

        jdbc.update("""
                INSERT INTO users (
                    email, password, name, birth, status,
                    created_at, updated_at
                ) VALUES (?, 'test-hash', '정리 테스트',
                    '1990-01-01', 'ACTIVE', NOW(6), NOW(6))
                """, key + "@example.invalid");

        userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?",
                Long.class,
                key + "@example.invalid"
        );

        jdbc.update("""
                INSERT INTO recipient_profiles (
                    recipient_id, profile_status,
                    source_version, analyzed_source_version,
                    created_at, updated_at
                ) VALUES (?, 'COMPLETED', 1000, 1000, NOW(6), NOW(6))
                """, userId);

        profileId = jdbc.queryForObject(
                "SELECT id FROM recipient_profiles WHERE recipient_id = ?",
                Long.class,
                userId
        );

        jdbc.update("""
                INSERT INTO categories (name, created_at, updated_at)
                VALUES (?, NOW(6), NOW(6))
                """, key);

        categoryId = jdbc.queryForObject(
                "SELECT id FROM categories WHERE name = ?",
                Long.class,
                key
        );

        jdbc.update("""
                INSERT INTO products (
                    category_id, name, brand, price, quantity,
                    views, sales, created_at, updated_at
                ) VALUES (?, ?, 'test', 1000, 1, 0, 0, NOW(6), NOW(6))
                """, categoryId, key);

        productId = jdbc.queryForObject(
                "SELECT id FROM products WHERE name = ?",
                Long.class,
                key
        );

        jdbc.update("""
                INSERT INTO recipient_recommended_products (
                    recipient_id, product_id, rank_order,
                    source_version, created_at, updated_at
                ) VALUES (?, ?, 1, 1000, NOW(6), NOW(6))
                """, userId, productId);

        version = 0;
    }

    @AfterEach
    void tearDown() {
        jdbc.update(
                "DELETE FROM profiling_jobs WHERE profile_id = ?",
                profileId
        );
        jdbc.update(
                "DELETE FROM recipient_recommended_products WHERE recipient_id = ?",
                userId
        );
        jdbc.update(
                "DELETE FROM recipient_profiles WHERE id = ?",
                profileId
        );
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("보존 기간 경계와 프로필·추천 보존을 확인한다")
    void respectsRetentionBoundariesAndPreservesDomainData() {
        var profileBefore = jdbc.queryForMap(
                "SELECT * FROM recipient_profiles WHERE id = ?",
                profileId
        );
        var recommendationsBefore = jdbc.queryForList(
                "SELECT * FROM recipient_recommended_products WHERE recipient_id = ?",
                userId
        );

        LocalDateTime completedBefore = NOW.minusDays(7);
        LocalDateTime failedBefore = NOW.minusDays(30);

        String completedOld = insert(
                "COMPLETED", "PUBLISHED",
                completedBefore.minusNanos(1_000)
        );
        String completedBoundary = insert(
                "COMPLETED", "PUBLISHED", completedBefore
        );
        String failedOld = insert(
                "FAILED", "PUBLISHED",
                failedBefore.minusNanos(1_000)
        );
        String failedBoundary = insert(
                "FAILED", "PUBLISHED", failedBefore
        );

        assertThat(store.deleteBatch(
                ProfilingResultStatus.COMPLETED,
                completedBefore,
                100
        )).isEqualTo(1);

        assertThat(store.deleteBatch(
                ProfilingResultStatus.FAILED,
                failedBefore,
                100
        )).isEqualTo(1);

        assertThat(exists(completedOld)).isFalse();
        assertThat(exists(failedOld)).isFalse();
        assertThat(exists(completedBoundary)).isTrue();
        assertThat(exists(failedBoundary)).isTrue();

        assertThat(jdbc.queryForMap(
                "SELECT * FROM recipient_profiles WHERE id = ?",
                profileId
        )).isEqualTo(profileBefore);

        assertThat(jdbc.queryForList(
                "SELECT * FROM recipient_recommended_products WHERE recipient_id = ?",
                userId
        )).isEqualTo(recommendationsBefore);
    }

    @Test
    @DisplayName("대기·대체·차단·활성 작업과 종료 시각 없는 작업은 삭제하지 않는다")
    void excludesNonTerminalAndActiveJobs() {
        LocalDateTime old = NOW.minusDays(60);

        insert("WAITING", "PUBLISHED", old);
        insert("SUPERSEDED", "PUBLISHED", old);
        insert("COMPLETED", "BLOCKED", old);
        insert("COMPLETED", "READY", old);
        insert("COMPLETED", "CLAIMED", old);
        insert("COMPLETED", "RETRY_WAIT", old);
        insert("COMPLETED", "PUBLISHED", null);

        String claimed = insert("COMPLETED", "PUBLISHED", old);
        jdbc.update("""
                UPDATE profiling_jobs
                SET claim_token = ?, claimed_at = ?
                WHERE request_id = ?
                """, UUID.randomUUID().toString(), NOW, claimed);

        String retrying = insert("COMPLETED", "PUBLISHED", old);
        jdbc.update("""
                UPDATE profiling_jobs SET next_attempt_at = ?
                WHERE request_id = ?
                """, NOW, retrying);

        assertThat(store.deleteBatch(
                ProfilingResultStatus.COMPLETED,
                NOW.minusDays(7),
                100
        )).isZero();

        assertThatThrownBy(() -> store.deleteBatch(
                ProfilingResultStatus.WAITING,
                NOW,
                100
        )).isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("삭제는 지정한 작은 배치 크기를 넘지 않는다")
    void limitsDeletionBatch() {
        for (int i = 0; i < 3; i++) {
            insert("COMPLETED", "PUBLISHED", NOW.minusDays(60));
        }

        assertThat(store.deleteBatch(
                ProfilingResultStatus.COMPLETED,
                NOW.minusDays(7),
                2
        )).isEqualTo(2);

        assertThat(store.deleteBatch(
                ProfilingResultStatus.COMPLETED,
                NOW.minusDays(7),
                2
        )).isEqualTo(1);
    }

    @Test
    @DisplayName("최대 1000건 배치를 허용하고 상한 초과 요청은 삭제 전에 거부한다")
    void acceptsConfiguredBatchSizeAndRejectsAboveLimit() {
        String id = insert("COMPLETED", "PUBLISHED", NOW.minusDays(60));

        assertThatThrownBy(() -> store.deleteBatch(
                ProfilingResultStatus.COMPLETED, NOW.minusDays(7), 1_001
        )).isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(exists(id)).isTrue();

        assertThat(store.deleteBatch(
                ProfilingResultStatus.COMPLETED, NOW.minusDays(7), 1_000
        )).isEqualTo(1);
        assertThat(exists(id)).isFalse();
    }

    @Test
    @DisplayName("다른 트랜잭션이 상태를 변경하면 삭제 시 최신 조건을 확인한다")
    void rechecksStateAfterConcurrentUpdate() throws Exception {
        String id = insert(
                "COMPLETED", "PUBLISHED", NOW.minusDays(60)
        );

        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> writer = executor.submit(() ->
                    transaction.executeWithoutResult(status -> {
                        jdbc.update("""
                                UPDATE profiling_jobs
                                SET result_status = 'WAITING',
                                    finished_at = NULL
                                WHERE request_id = ?
                                """, id);

                        updated.countDown();

                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                        "동시 갱신 테스트 제한 시간 초과"
                                );
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    })
            );

            Future<Integer> deletion = null;

            try {
                assertThat(updated.await(5, TimeUnit.SECONDS)).isTrue();

                deletion = executor.submit(() ->
                        store.deleteBatch(
                                ProfilingResultStatus.COMPLETED,
                                NOW.minusDays(7),
                                100
                        )
                );

                Future<Integer> pendingDeletion = deletion;

                assertThatThrownBy(() ->
                        pendingDeletion.get(200, TimeUnit.MILLISECONDS)
                ).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }

            writer.get(5, TimeUnit.SECONDS);
            assertThat(deletion).isNotNull();
            assertThat(deletion.get(5, TimeUnit.SECONDS)).isZero();
            assertThat(exists(id)).isTrue();
        }
    }

    private String insert(
            String resultStatus,
            String publishStatus,
            LocalDateTime finishedAt
    ) {
        String id = UUID.randomUUID().toString();

        LocalDateTime publishedAt =
                "PUBLISHED".equals(publishStatus)
                        ? NOW.minusDays(90)
                        : null;

        jdbc.update("""
                INSERT INTO profiling_jobs (
                    request_id, profile_id, source_version,
                    request_body, publish_status, result_status,
                    published_at, finished_at, created_at, updated_at
                ) VALUES (?, ?, ?, '{}', ?, ?, ?, ?, ?, ?)
                """,
                id,
                profileId,
                ++version,
                publishStatus,
                resultStatus,
                publishedAt,
                finishedAt,
                NOW.minusYears(1),
                NOW
        );

        return id;
    }

    private boolean exists(String requestId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM profiling_jobs WHERE request_id = ?",
                Integer.class,
                requestId
        ) == 1;
    }
}
