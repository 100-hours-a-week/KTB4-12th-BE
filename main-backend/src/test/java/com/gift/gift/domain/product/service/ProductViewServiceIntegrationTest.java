package com.gift.gift.domain.product.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gift.gift.domain.product.controller.ProductController;
import com.gift.gift.domain.product.repository.ProductViewEventRepository;
import com.gift.gift.domain.product.repository.ProductViewRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest
class ProductViewServiceIntegrationTest {

    private static final String PASSWORD_HASH =
            "$2a$10$ZUYQG3Jm25Pimrzy9y7/EDJY4bopDMj/XRFMg8QZyGXLc2x7uCU/6";

    @MockitoSpyBean
    private ProductViewService productViewService;

    @Autowired
    private ProductDetailService productDetailService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private ProductViewEventRepository productViewEventRepository;

    @Autowired
    private ProductViewBatchService productViewBatchService;

    @MockitoSpyBean
    private ProductViewRepository productViewRepository;

    @Autowired
    private ProductController productController;

    private final List<Long> userIds = new ArrayList<>();

    private Long rootCategoryId;
    private Long leafCategoryId;
    private Long productId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();

        jdbcTemplate.update("""
                INSERT INTO categories (name, created_at, updated_at)
                VALUES (?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, "조회수 대분류-" + suffix);
        rootCategoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = ?",
                Long.class,
                "조회수 대분류-" + suffix
        );

        jdbcTemplate.update("""
                INSERT INTO categories (parent_id, name, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, rootCategoryId, "조회수 소분류-" + suffix);
        leafCategoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = ?",
                Long.class,
                "조회수 소분류-" + suffix
        );

        jdbcTemplate.update("""
                INSERT INTO products (
                    category_id, name, brand, description,
                    price, quantity, views, sales, created_at, updated_at
                )
                VALUES (
                    ?, ?, '테스트 브랜드', NULL,
                    10000, 100, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
                )
                """, leafCategoryId, "조회수 테스트 상품-" + suffix);
        productId = jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM products
                WHERE category_id = ? AND name = ?
                """,
                Long.class,
                leafCategoryId,
                "조회수 테스트 상품-" + suffix
        );
    }

    @AfterEach
    void tearDown() {
        reset(productViewEventRepository, productViewRepository, productViewService);

        if (productId != null) {
            jdbcTemplate.update("DELETE FROM product_view_events WHERE product_id = ?", productId);
            jdbcTemplate.update(
                    "DELETE FROM product_views WHERE product_id = ?",
                    productId
            );
            jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
        }
        if (leafCategoryId != null) {
            jdbcTemplate.update("DELETE FROM categories WHERE id = ?", leafCategoryId);
        }
        if (rootCategoryId != null) {
            jdbcTemplate.update("DELETE FROM categories WHERE id = ?", rootCategoryId);
        }
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    @DisplayName("상세 요청 스레드에서 조회 기록을 저장하고 반환 전에 커밋한다")
    void getProductDetail_commitsHistoryOnRequestThread() {
        Long userId = createUser();
        Thread requestThread = Thread.currentThread();
        AtomicInteger commits = new AtomicInteger();
        doAnswer(invocation -> {
            assertThat(Thread.currentThread()).isSameAs(requestThread);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) {
                        commits.incrementAndGet();
                    }
                }
            });
            return invocation.callRealMethod();
        }).when(productViewService).recordProductView(userId, productId);

        assertThat(productDetailService.getProductDetail(userId, productId).product().productId())
                .isEqualTo(productId);

        assertThat(commits.get()).isEqualTo(1);
        assertThat(viewHistoryCount(userId)).isEqualTo(1);
        assertThat(viewCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("조회 기록 저장에 실패하면 상세 요청도 실패하고 이력을 롤백한다")
    void getProductDetail_rollsBackWhenEventInsertFails() {
        Long userId = createUser();
        doThrow(new DataAccessResourceFailureException("의도한 증가 실패"))
                .when(productViewEventRepository).insertEvent(userId, productId);

        assertThatThrownBy(() -> productDetailService.getProductDetail(userId, productId))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(viewHistoryCount(userId)).isZero();
        assertThat(viewCount()).isZero();
    }

    @Test
    @DisplayName("최초 조회만 집계하고 24시간 이내 반복 조회는 집계하지 않는다")
    void recordProductView_countsFirstViewOnlyWithinCooldown() {
        Long userId = createUser();

        productViewService.recordProductView(userId, productId);
        LocalDateTime firstViewedAt = lastViewedAt(userId);

        productViewService.recordProductView(userId, productId);

        assertThat(viewCount()).isEqualTo(1);
        assertThat(viewHistoryCount(userId)).isEqualTo(1);
        assertThat(lastViewedAt(userId)).isEqualTo(firstViewedAt);
    }

    @Test
    @DisplayName("23시간 59분이 지나도 다시 집계하지 않는다")
    void recordProductView_doesNotCountBeforeTwentyFourHours() {
        Long userId = createUser();
        productViewService.recordProductView(userId, productId);
        setLastViewedAt("CURRENT_TIMESTAMP(6) - INTERVAL 23 HOUR - INTERVAL 59 MINUTE", userId);
        LocalDateTime beforeRetry = lastViewedAt(userId);

        productViewService.recordProductView(userId, productId);

        assertThat(viewCount()).isEqualTo(1);
        assertThat(lastViewedAt(userId)).isEqualTo(beforeRetry);
    }

    @Test
    @DisplayName("정확히 24시간이 지나면 다시 집계한다")
    void recordProductView_countsAtTwentyFourHourBoundary() {
        Long userId = createUser();
        productViewService.recordProductView(userId, productId);
        setLastViewedAt("CURRENT_TIMESTAMP(6) - INTERVAL 24 HOUR", userId);
        LocalDateTime previousViewedAt = lastViewedAt(userId);

        productViewService.recordProductView(userId, productId);

        assertThat(viewCount()).isEqualTo(2);
        assertThat(lastViewedAt(userId)).isAfter(previousViewedAt);
    }

    @Test
    @DisplayName("날짜가 바뀌어도 24시간이 지나지 않으면 다시 집계하지 않는다")
    void recordProductView_doesNotCountByCalendarDate() {
        Long userId = createUser();
        productViewService.recordProductView(userId, productId);
        setLastViewedAt("CURRENT_DATE - INTERVAL 1 MICROSECOND", userId);
        LocalDateTime previousViewedAt = lastViewedAt(userId);

        productViewService.recordProductView(userId, productId);

        assertThat(viewCount()).isEqualTo(1);
        assertThat(lastViewedAt(userId)).isEqualTo(previousViewedAt);
    }

    @Test
    @DisplayName("동일 사용자와 상품의 동시 최초 조회는 한 번만 집계한다")
    void recordProductView_countsConcurrentFirstViewOnce() throws Exception {
        Long userId = createUser();

        invokeConcurrently(List.of(userId, userId, userId, userId, userId));

        assertThat(viewHistoryCount(userId)).isEqualTo(1);
        assertThat(viewCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("여러 사용자의 동시 조회는 누락 없이 모두 집계한다")
    void recordProductView_countsConcurrentViewsFromDifferentUsers() throws Exception {
        List<Long> concurrentUserIds = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            concurrentUserIds.add(createUser());
        }

        invokeConcurrently(concurrentUserIds);

        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_views WHERE product_id = ?",
                Integer.class,
                productId
        );
        assertThat(historyCount).isEqualTo(concurrentUserIds.size());
        assertThat(viewCount()).isEqualTo(concurrentUserIds.size());
    }

    @Test
    @DisplayName("조회 이벤트 저장이 실패하면 이력도 함께 롤백한다")
    void recordProductView_rollsBackHistoryWhenEventInsertFails() {
        Long userId = createUser();
        doThrow(new DataAccessResourceFailureException("의도한 증가 실패"))
                .when(productViewEventRepository)
                .insertEvent(userId, productId);

        assertThatThrownBy(() ->
                productViewService.recordProductView(userId, productId)
        ).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(viewHistoryCount(userId)).isZero();
        assertThat(viewCount()).isZero();
    }

    @Test
    @DisplayName("통계 행이 없으면 이벤트를 완료하지 않아 재처리할 수 있다")
    void recordProductView_preservesEventWhenStatsMissing() {
        Long userId = createUser();
        jdbcTemplate.update("DELETE FROM product_view_stats WHERE product_id = ?", productId);

        productViewService.recordProductView(userId, productId);
        assertThatThrownBy(() -> productViewBatchService.processPendingEvents())
                .isInstanceOf(IllegalStateException.class);
        assertThat(viewHistoryCount(userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_view_events WHERE product_id = ? AND processed_at IS NULL",
                Integer.class, productId)).isEqualTo(1);
    }

    @Test
    @DisplayName("상품 생성·삭제·복구 시 통계 상태가 연동되고 레거시 조회수는 갱신하지 않는다")
    void productTriggers_synchronizeStatsLifecycle() {
        assertThat(viewCount()).isZero();
        productViewService.recordProductView(createUser(), productId);
        assertThat(viewCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT views FROM products WHERE id = ?",
                Integer.class, productId)).isZero();

        jdbcTemplate.update("UPDATE products SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", productId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM product_view_stats WHERE product_id = ?",
                Boolean.class, productId)).isTrue();
        jdbcTemplate.update("UPDATE products SET deleted_at = NULL WHERE id = ?", productId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NULL FROM product_view_stats WHERE product_id = ?",
                Boolean.class, productId)).isTrue();
        assertThat(viewCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("상품 물리 삭제 시 통계 행도 삭제한다")
    void productTriggers_removeStatsOnPhysicalDeletion() {
        jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_view_stats WHERE product_id = ?", Integer.class, productId)).isZero();
    }

    @Test
    @DisplayName("쿨다운 만료 동시 조회는 한 번만 이벤트를 생성한다")
    void recordProductView_countsConcurrentExpiredViewOnce() throws Exception {
        Long userId = createUser();
        productViewService.recordProductView(userId, productId);
        setLastViewedAt("CURRENT_TIMESTAMP(6) - INTERVAL 24 HOUR", userId);
        LocalDateTime previousViewedAt = lastViewedAt(userId);

        invokeConcurrently(List.of(userId, userId, userId, userId, userId));

        assertThat(viewHistoryCount(userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_view_events WHERE product_id = ?", Integer.class, productId))
                .isEqualTo(2);
        assertThat(lastViewedAt(userId)).isAfter(previousViewedAt);
    }

    @Test
    @DisplayName("풀 크기보다 많은 상세 동시 요청도 요청 스레드에서 저장을 완료한다")
    void getProductDetail_completesConcurrentRequestsBeyondPoolSize() throws Exception {
        List<Long> viewers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            viewers.add(createUser());
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(viewers.size());
        List<Future<?>> requests = new ArrayList<>();
        try {
            for (Long viewer : viewers) {
                requests.add(callers.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("상세 조회 시작 대기 시간 초과");
                    }
                    productDetailService.getProductDetail(viewer, productId);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> request : requests) {
                request.get(20, TimeUnit.SECONDS);
            }
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM product_view_events WHERE product_id = ?", Integer.class, productId)).isEqualTo(viewers.size());
            for (Long viewer : viewers) {
                assertThat(viewHistoryCount(viewer)).isEqualTo(1);
            }
            assertThat(productViewBatchService.processPendingEvents()).isEqualTo(viewers.size());
            assertThat(viewCount()).isEqualTo(viewers.size());
        } finally {
            start.countDown();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("상세 반환 전에 이벤트를 커밋하고 조회수 통계는 배치 실행까지 갱신하지 않는다")
    void getProductDetail_commitsEventBeforeReturning() {
        Long userId = createUser();
        AtomicBoolean committed = new AtomicBoolean();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    committed.set(status == STATUS_COMMITTED);
                }
            });
            return invocation.callRealMethod();
        }).when(productViewService).recordProductView(userId, productId);

        assertThat(productController.getProductDetail(productId.toString(), () -> userId.toString())
                .data().product().productId()).isEqualTo(productId);
        assertThat(committed).isTrue();
        assertThat(viewHistoryCount(userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_view_events WHERE product_id = ? AND processed_at IS NULL",
                Integer.class, productId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT views FROM product_view_stats WHERE product_id = ?", Integer.class, productId)).isZero();
        assertThat(viewCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("쿨다운 중에도 상품 공유 잠금은 조회 트랜잭션 완료까지 삭제를 막는다")
    void recordProductView_serializesSoftDeleteDuringCooldown() throws Exception {
        Long userId = createUser();
        productViewService.recordProductView(userId, productId);
        CountDownLatch atWrite = new CountDownLatch(1);
        CountDownLatch continueWrite = new CountDownLatch(1);
        doAnswer(invocation -> {
            atWrite.countDown();
            if (!continueWrite.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("조회 기록 대기 시간 초과");
            }
            return 0;
        }).when(productViewRepository).recordViewAfterCooldown(userId, productId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> view = executor.submit(() -> productViewService.recordProductView(userId, productId));
            assertThat(atWrite.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> deletion = executor.submit(() -> jdbcTemplate.update(
                    "UPDATE products SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", productId));
            assertThatThrownBy(() -> deletion.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            continueWrite.countDown();
            view.get(10, TimeUnit.SECONDS);
            deletion.get(10, TimeUnit.SECONDS);
        } finally {
            continueWrite.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Long createUser() {
        String suffix = UUID.randomUUID().toString();
        String email = "view-" + suffix + "@example.com";

        jdbcTemplate.update("""
                INSERT INTO users (
                    email, password, name, birth, status,
                    is_birthday_public, is_first_login, created_at, updated_at
                )
                VALUES (
                    ?, ?, '조회사용자', '2000-01-01', 'ACTIVE',
                    FALSE, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
                )
                """, email, PASSWORD_HASH);

        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?",
                Long.class,
                email
        );
        userIds.add(userId);
        return userId;
    }

    private void setLastViewedAt(String expression, Long userId) {
        jdbcTemplate.update(
                """
                UPDATE product_views
                SET last_viewed_at = %s
                WHERE user_id = ? AND product_id = ?
                """.formatted(expression),
                userId,
                productId
        );
    }

    private int viewCount() {
        productViewBatchService.processPendingEvents();
        return jdbcTemplate.queryForObject(
                "SELECT views FROM product_view_stats WHERE product_id = ?",
                Integer.class,
                productId
        );
    }

    private int viewHistoryCount(Long userId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM product_views
                WHERE user_id = ? AND product_id = ?
                """,
                Integer.class,
                userId,
                productId
        );
    }

    private LocalDateTime lastViewedAt(Long userId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT last_viewed_at
                FROM product_views
                WHERE user_id = ? AND product_id = ?
                """,
                LocalDateTime.class,
                userId,
                productId
        );
    }

    private void invokeConcurrently(List<Long> concurrentUserIds) throws Exception {
        int taskCount = concurrentUserIds.size();
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (Long userId : concurrentUserIds) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 실행 대기 시간 초과");
                    }
                    productViewService.recordProductView(userId, productId);
                    return null;
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
