package com.gift.gift.domain.product.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.product.repository.ProductViewEventRepository;
import com.gift.gift.domain.product.repository.ProductViewStatsRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest
class ProductViewBatchServiceIntegrationTest {

    private static final String PASSWORD_HASH =
            "$2a$10$ZUYQG3Jm25Pimrzy9y7/EDJY4bopDMj/XRFMg8QZyGXLc2x7uCU/6";

    @Autowired
    private ProductViewService productViewService;

    @Autowired
    private ProductViewBatchService productViewBatchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private ProductViewStatsRepository productViewStatsRepository;

    @MockitoSpyBean
    private ProductViewEventRepository productViewEventRepository;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();

    private Long rootCategoryId;
    private Long leafCategoryId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();

        jdbcTemplate.update("""
                INSERT INTO categories (name, created_at, updated_at)
                VALUES (?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, "Batch 대분류-" + suffix);
        rootCategoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = ?",
                Long.class,
                "Batch 대분류-" + suffix
        );

        jdbcTemplate.update("""
                INSERT INTO categories (parent_id, name, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, rootCategoryId, "Batch 소분류-" + suffix);
        leafCategoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = ?",
                Long.class,
                "Batch 소분류-" + suffix
        );
    }

    @AfterEach
    void tearDown() {
        reset(productViewStatsRepository, productViewEventRepository);

        for (Long productId : productIds) {
            jdbcTemplate.update(
                    "DELETE FROM product_view_events WHERE product_id = ?",
                    productId
            );
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
    @DisplayName("Batch는 상품별 Event 수만큼 조회수를 증가시키고 완료 처리한다")
    void processPendingEvents_aggregatesByProductAndMarksProcessed() {
        Long firstProductId = createProduct();
        Long secondProductId = createProduct();

        recordView(firstProductId);
        recordView(firstProductId);
        recordView(firstProductId);
        recordView(secondProductId);
        recordView(secondProductId);

        assertThat(productViewBatchService.processPendingEvents()).isEqualTo(5);
        assertThat(viewCount(firstProductId)).isEqualTo(3);
        assertThat(viewCount(secondProductId)).isEqualTo(2);
        assertThat(pendingEventCount()).isZero();

        assertThat(productViewBatchService.processPendingEvents()).isZero();
        assertThat(viewCount(firstProductId)).isEqualTo(3);
        assertThat(viewCount(secondProductId)).isEqualTo(2);
    }

    @Test
    @DisplayName("Batch 처리 중 예외가 발생하면 조회수와 완료 상태를 함께 롤백한다")
    void processPendingEvents_rollsBackWhenProductUpdateFails() {
        Long productId = createProduct();
        recordView(productId);

        doThrow(new DataAccessResourceFailureException("의도한 Batch 실패"))
                .when(productViewStatsRepository)
                .incrementViewsIfActive(productId, 1);

        assertThatThrownBy(() -> productViewBatchService.processPendingEvents())
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(viewCount(productId)).isZero();
        assertThat(pendingEventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이벤트 완료 표시가 실패하면 이미 증가시킨 통계도 롤백한다")
    void processPendingEvents_rollsBackStatsWhenMarkProcessedFails() {
        Long productId = createProduct();
        recordView(productId);
        doThrow(new DataAccessResourceFailureException("의도한 완료 표시 실패"))
                .when(productViewEventRepository).markProcessed(anyCollection());

        assertThatThrownBy(() -> productViewBatchService.processPendingEvents())
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(viewCount(productId)).isZero();
        assertThat(pendingEventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("비활성 상품 Event는 완료 처리하지만 조회수는 증가시키지 않는다")
    void processPendingEvents_skipsDeletedProduct() {
        Long productId = createProduct();
        recordView(productId);
        jdbcTemplate.update(
                "UPDATE products SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                productId
        );

        assertThat(productViewBatchService.processPendingEvents()).isEqualTo(1);
        assertThat(viewCount(productId)).isZero();
        assertThat(pendingEventCount()).isZero();
    }

    @Test
    @DisplayName("통계 행이 없으면 이벤트를 완료 처리하지 않고 롤백한다")
    void processPendingEvents_preservesEventsWhenStatsMissing() {
        Long productId = createProduct();
        recordView(productId);
        jdbcTemplate.update("DELETE FROM product_view_stats WHERE product_id = ?", productId);

        assertThatThrownBy(() -> productViewBatchService.processPendingEvents())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("상품 조회수 통계 행이 없습니다.");
        assertThat(pendingEventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("통계 갱신 트랜잭션이 열려 있어도 상품 상세의 공유 잠금 획득은 완료된다")
    void statsUpdate_doesNotBlockProductDetailSharedLock() throws Exception {
        Long productId = createProduct();
        Long userId = createUser();
        CountDownLatch statsLocked = new CountDownLatch(1);
        CountDownLatch releaseStats = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> update = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        assertThat(productViewStatsRepository.incrementViewsIfActive(productId, 1)).isEqualTo(1);
                        statsLocked.countDown();
                        try {
                            await(releaseStats);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    })
            );
            assertThat(statsLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> detail = executor.submit(() -> productViewService.recordProductView(userId, productId));

            detail.get(3, TimeUnit.SECONDS);
            assertThat(update.isDone()).isFalse();
            releaseStats.countDown();
            update.get(10, TimeUnit.SECONDS);
            assertThat(viewCount(productId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM product_views WHERE product_id = ?", Long.class, productId
            )).isEqualTo(1);
            assertThat(pendingEventCount()).isEqualTo(1);
        } finally {
            releaseStats.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("두 Batch worker가 동시에 실행되어도 Event를 중복 집계하지 않는다")
    void processPendingEvents_processesConcurrentWorkersWithoutDuplication()
            throws Exception {
        Long productId = createProduct();
        for (int index = 0; index < 10; index++) {
            recordView(productId);
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = List.of(
                    executor.submit(() -> {
                        await(start);
                        return productViewBatchService.processPendingEvents();
                    }),
                    executor.submit(() -> {
                        await(start);
                        return productViewBatchService.processPendingEvents();
                    })
            );
            start.countDown();

            int processedCount = futures.get(0).get(30, TimeUnit.SECONDS)
                    + futures.get(1).get(30, TimeUnit.SECONDS);

            assertThat(processedCount).isEqualTo(10);
            assertThat(viewCount(productId)).isEqualTo(10);
            assertThat(pendingEventCount()).isZero();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("실패한 이벤트는 새 처리기에서도 복구하고 한 번만 집계한다")
    void processPendingEvents_recoversWithNewProcessorAfterFailure() {
        Long productId = createProduct();
        recordView(productId);
        doThrow(new DataAccessResourceFailureException("의도한 완료 표시 실패"))
                .when(productViewEventRepository).markProcessed(anyCollection());
        assertThatThrownBy(() -> productViewBatchService.processPendingEvents())
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(viewCount(productId)).isZero();
        assertThat(pendingEventCount()).isEqualTo(1);
        reset(productViewEventRepository);

        ProductViewBatchService recovered = new ProductViewBatchService(
                productViewStatsRepository, productViewEventRepository, 500);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Integer processed = transaction.execute(status -> recovered.processPendingEvents());
        assertThat(processed).isEqualTo(1);
        assertThat(viewCount(productId)).isEqualTo(1);
        assertThat(pendingEventCount()).isZero();
        Integer retried = transaction.execute(status -> recovered.processPendingEvents());
        assertThat(retried).isZero();
    }

    @Test
    @DisplayName("배치 크기를 넘는 이벤트는 여러 실행으로 나누어 모두 처리한다")
    void processPendingEvents_respectsBatchSizeWithoutLosingRemainder() {
        Long productId = createProduct();
        for (int i = 0; i < 5; i++) {
            recordView(productId);
        }
        ProductViewBatchService smallBatch = new ProductViewBatchService(
                productViewStatsRepository, productViewEventRepository, 2);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        List<Integer> processedCounts = List.of(
                transaction.execute(status -> smallBatch.processPendingEvents()),
                transaction.execute(status -> smallBatch.processPendingEvents()),
                transaction.execute(status -> smallBatch.processPendingEvents()),
                transaction.execute(status -> smallBatch.processPendingEvents())
        );
        assertThat(processedCounts).containsExactly(2, 2, 1, 0);
        assertThat(viewCount(productId)).isEqualTo(5);
        assertThat(pendingEventCount()).isZero();
    }

    private void recordView(Long productId) {
        productViewService.recordProductView(createUser(), productId);
    }

    private Long createProduct() {
        String name = "Batch 상품-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO products (
                    category_id, name, brand, description,
                    price, quantity, views, sales, created_at, updated_at
                )
                VALUES (?, ?, '테스트 브랜드', NULL,
                        10000, 100, 0, 0,
                        CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, leafCategoryId, name);
        Long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE name = ?",
                Long.class,
                name
        );
        productIds.add(productId);
        return productId;
    }

    private Long createUser() {
        String email = "batch-view-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO users (
                    email, password, name, birth, status,
                    is_birthday_public, is_first_login, created_at, updated_at
                )
                VALUES (?, ?, '조회사용자', '2000-01-01', 'ACTIVE',
                        FALSE, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, email, PASSWORD_HASH);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?",
                Long.class,
                email
        );
        userIds.add(userId);
        return userId;
    }

    private int viewCount(Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT views FROM product_view_stats WHERE product_id = ?",
                Integer.class,
                productId
        );
    }

    private int pendingEventCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_view_events WHERE processed_at IS NULL",
                Integer.class
        );
    }

    private void await(CountDownLatch start) throws InterruptedException {
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시 Batch 실행 대기 시간 초과");
        }
    }
}
