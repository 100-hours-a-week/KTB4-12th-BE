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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ProductStockServiceIntegrationTest {

    private static final LocalDateTime INITIAL_UPDATED_AT =
            LocalDateTime.of(2020, 1, 1, 0, 0);

    @Autowired
    private ProductStockService productStockService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long rootCategoryId;
    private Long leafCategoryId;
    private Long productId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        String rootName = "재고 대분류-" + suffix;
        String leafName = "재고 소분류-" + suffix;
        String productName = "재고 상품-" + suffix;

        jdbcTemplate.update("""
                INSERT INTO categories (name, created_at, updated_at)
                VALUES (?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, rootName);

        rootCategoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = ?",
                Long.class,
                rootName
        );

        jdbcTemplate.update("""
                INSERT INTO categories (parent_id, name, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, rootCategoryId, leafName);

        leafCategoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = ?",
                Long.class,
                leafName
        );

        jdbcTemplate.update("""
                INSERT INTO products (
                    category_id, name, brand, price,
                    quantity, views, sales, created_at, updated_at
                )
                VALUES (?, ?, '테스트 브랜드', 10000, 10, 0, 4, ?, ?)
                """, leafCategoryId, productName,
                INITIAL_UPDATED_AT, INITIAL_UPDATED_AT);

        productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE category_id = ? AND name = ?",
                Long.class,
                leafCategoryId,
                productName
        );
    }

    @AfterEach
    void tearDown() {
        if (productId != null) {
            jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
        }
        if (leafCategoryId != null) {
            jdbcTemplate.update("DELETE FROM categories WHERE id = ?", leafCategoryId);
        }
        if (rootCategoryId != null) {
            jdbcTemplate.update("DELETE FROM categories WHERE id = ?", rootCategoryId);
        }
    }

    @Test
    @DisplayName("재고 차감과 판매량 증가 및 수정 시각 갱신이 함께 적용된다")
    void deductStockIfAvailable_updatesStockAndSales() {
        boolean result = productStockService.deductStockIfAvailable(productId, 3);

        assertThat(result).isTrue();
        StockState state = readState();
        assertThat(state.quantity()).isEqualTo(7);
        assertThat(state.sales()).isEqualTo(7);
        assertThat(state.updatedAt()).isAfter(INITIAL_UPDATED_AT);
    }

    @Test
    @DisplayName("재고가 부족하면 재고와 판매량 및 수정 시각이 유지된다")
    void deductStockIfAvailable_preservesStateWhenInsufficient() {
        StockState before = readState();

        boolean result = productStockService.deductStockIfAvailable(productId, 11);

        assertThat(result).isFalse();
        assertThat(readState()).isEqualTo(before);
    }

    @Test
    @DisplayName("남은 재고 전부를 차감한 뒤 추가 요청은 실패한다")
    void deductStockIfAvailable_allowsExactStockThenRejects() {
        assertThat(productStockService.deductStockIfAvailable(productId, 10))
                .isTrue();

        StockState soldOut = readState();
        assertThat(soldOut.quantity()).isZero();
        assertThat(soldOut.sales()).isEqualTo(14);

        assertThat(productStockService.deductStockIfAvailable(productId, 1))
                .isFalse();
        assertThat(readState()).isEqualTo(soldOut);
    }

    @Test
    @DisplayName("존재하지 않는 상품도 변경된 행이 없으므로 false를 반환한다")
    void deductStockIfAvailable_returnsFalseWhenProductDoesNotExist() {
        Long missingProductId = productId;
        jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);

        assertThat(productStockService.deductStockIfAvailable(missingProductId, 1))
                .isFalse();
    }

    @Test
    @DisplayName("호출자 트랜잭션이 실패하면 재고와 판매량도 함께 롤백된다")
    void deductStockIfAvailable_rollsBackWithCaller() {
        StockState before = readState();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() ->
                transaction.executeWithoutResult(status -> {
                    assertThat(productStockService.deductStockIfAvailable(productId, 3))
                            .isTrue();
                    assertThat(readState().quantity()).isEqualTo(7);

                    throw new IllegalStateException("호출자 처리 실패");
                })
        ).isInstanceOf(IllegalStateException.class)
                .hasMessage("호출자 처리 실패");

        assertThat(readState()).isEqualTo(before);
    }

    @Test
    @DisplayName("MySQL CHECK 제약이 직접 SQL을 통한 음수 재고 저장도 차단한다")
    void quantityCheck_rejectsNegativeStock() {
        StockState before = readState();

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "UPDATE products SET quantity = -1 WHERE id = ?",
                        productId
                )
        ).isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("chk_products_quantity_non_negative");

        assertThat(readState()).isEqualTo(before);
    }

    @Test
    @DisplayName("동시에 재고보다 많은 요청이 와도 재고만큼만 성공한다")
    void deductStockIfAvailable_preventsOversellingUnderConcurrency() throws Exception {
        int requestCount = 20;
        int quantityPerRequest = 2;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < requestCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 실행 대기 시간 초과");
                    }

                    return productStockService.deductStockIfAvailable(
                            productId,
                            quantityPerRequest
                    );
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successCount = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    successCount++;
                }
            }

            assertThat(successCount).isEqualTo(5);
            assertThat(requestCount - successCount).isEqualTo(15);

            StockState state = readState();
            assertThat(state.quantity()).isZero();
            assertThat(state.sales()).isEqualTo(14);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private StockState readState() {
        return jdbcTemplate.queryForObject("""
                SELECT quantity, sales, updated_at
                FROM products
                WHERE id = ?
                """,
                (resultSet, rowNumber) -> new StockState(
                        resultSet.getInt("quantity"),
                        resultSet.getInt("sales"),
                        resultSet.getTimestamp("updated_at").toLocalDateTime()
                ),
                productId
        );
    }

    private record StockState(
            int quantity,
            int sales,
            LocalDateTime updatedAt
    ) {
    }
}
