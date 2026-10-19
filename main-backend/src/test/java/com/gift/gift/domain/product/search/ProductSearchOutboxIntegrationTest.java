package com.gift.gift.domain.product.search;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.product.service.ProductStockService;
import com.gift.gift.domain.product.service.ProductViewBatchService;
import com.gift.gift.domain.product.service.ProductViewService;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doCallRealMethod;

@SpringBootTest(properties = "app.product-search.outbox.enabled=true")
class ProductSearchOutboxIntegrationTest {

    @Autowired private ProductStockService stockService;
    @Autowired private ProductViewService viewService;
    @Autowired private ProductViewBatchService viewBatchService;
    @Autowired private ProductSearchOutboxWriter writer;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @MockitoSpyBean private ProductSearchOutboxRepository outbox;

    private Long rootId;
    private Long categoryId;
    private Long productId;
    private Long userId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO categories (name, created_at, updated_at)
                VALUES (?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, "outbox-root-" + suffix);
        rootId = jdbc.queryForObject("SELECT id FROM categories WHERE name = ?",
                Long.class, "outbox-root-" + suffix);
        jdbc.update("""
                INSERT INTO categories (parent_id, name, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, rootId, "outbox-leaf-" + suffix);
        categoryId = jdbc.queryForObject("SELECT id FROM categories WHERE name = ?",
                Long.class, "outbox-leaf-" + suffix);
        jdbc.update("""
                INSERT INTO products (category_id, name, brand, price, quantity, views, sales,
                    created_at, updated_at)
                VALUES (?, ?, '브랜드', 10000, 100, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, categoryId, "outbox-product-" + suffix);
        productId = jdbc.queryForObject("SELECT id FROM products WHERE category_id = ?",
                Long.class, categoryId);
        userId = users.saveAndFlush(new User(suffix + "@example.com",
                "$2a$10$" + "a".repeat(53), "Outbox 테스트", LocalDate.of(1990, 1, 1))).getId();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM product_search_outbox WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM product_view_events WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM product_views WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM categories WHERE id = ?", rootId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("판매·취소 변경마다 상품 버전과 미처리 작업이 함께 저장된다")
    void stockChangesAndCancellationRecordDistinctVersions() {
        assertThat(stockService.deductStockIfAvailable(productId, 3)).isTrue();
        assertThat(stockService.restoreStock(productId, 3)).isTrue();

        assertThat(value("quantity")).isEqualTo(100);
        assertThat(value("sales")).isZero();
        assertThat(value("search_version")).isEqualTo(2);
        assertThat(versions()).containsExactly(1L, 2L);
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM product_search_outbox WHERE product_id = ?",
                String.class, productId)).containsExactly("PENDING");
    }

    @Test
    @DisplayName("중복 조회는 조회수·버전·Outbox 기록을 추가하지 않는다")
    void repeatedViewWithinCooldownDoesNotCreateAnotherJob() {
        viewService.recordProductView(userId, productId);
        viewService.recordProductView(userId, productId);

        assertThat(versions()).isEmpty();
        assertThat(value("search_version")).isZero();
        assertThat(value("views")).isZero();
        assertThat(viewBatchService.processPendingEvents()).isEqualTo(1);

        assertThat(value("views")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT views FROM products WHERE id = ?",
                Long.class, productId)).isZero();
        assertThat(value("search_version")).isEqualTo(1);
        assertThat(versions()).containsExactly(1L);
    }

    @Test
    @DisplayName("실패한 재고 변경은 버전과 작업을 남기지 않는다")
    void unsuccessfulStockChangesDoNotCreateJobsOrAdvanceVersion() {
        assertThat(stockService.deductStockIfAvailable(productId, 101)).isFalse();
        assertThat(stockService.restoreStock(productId, 1)).isFalse();

        assertThat(value("search_version")).isZero();
        assertThat(versions()).isEmpty();
    }

    @Test
    @DisplayName("호출자 롤백 시 조회 이력·상품 변경·Outbox가 함께 취소된다")
    void callerRollbackRemovesAllProductChangesAndJobs() {
        assertThatThrownBy(() -> transaction().executeWithoutResult(tx -> {
            viewService.recordProductView(userId, productId);
            assertThat(viewBatchService.processPendingEvents()).isEqualTo(1);
            stockService.deductStockIfAvailable(productId, 3);
            assertThat(versions()).containsExactly(1L, 2L);
            throw new IllegalStateException("결제 후속 처리 실패");
        })).isInstanceOf(IllegalStateException.class).hasMessage("결제 후속 처리 실패");

        assertThat(value("quantity")).isEqualTo(100);
        assertThat(value("sales")).isZero();
        assertThat(value("views")).isZero();
        assertThat(value("search_version")).isZero();
        assertThat(versions()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_views WHERE product_id = ?",
                Integer.class, productId)).isZero();
    }

    @Test
    @DisplayName("Outbox 저장 실패 시 재고·판매수·버전도 롤백된다")
    void outboxFailureRollsBackStockChange() {
        doThrow(new DataAccessResourceFailureException("Outbox 저장 실패")).when(outbox).insert(productId);

        assertThatThrownBy(() -> stockService.deductStockIfAvailable(productId, 3))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(value("quantity")).isEqualTo(100);
        assertThat(value("sales")).isZero();
        assertThat(value("search_version")).isZero();
        assertThat(versions()).isEmpty();
    }

    @Test
    @DisplayName("Outbox 실패 시 배치 집계와 완료 처리를 롤백하고 조회 이벤트는 재처리한다")
    void outboxFailureRollsBackViewBatchAndAllowsRetry() {
        viewService.recordProductView(userId, productId);
        doThrow(new DataAccessResourceFailureException("Outbox 저장 실패")).when(outbox).insert(productId);

        assertThatThrownBy(() -> viewBatchService.processPendingEvents())
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(value("views")).isZero();
        assertThat(value("search_version")).isZero();
        assertThat(versions()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_views WHERE product_id = ?",
                Integer.class, productId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_view_events WHERE product_id = ? AND processed_at IS NULL",
                Integer.class, productId)).isEqualTo(1);

        doCallRealMethod().when(outbox).insert(productId);
        assertThat(viewBatchService.processPendingEvents()).isEqualTo(1);
        assertThat(value("views")).isEqualTo(1);
        assertThat(versions()).containsExactly(1L);
    }

    @Test
    @DisplayName("여러 조회 이벤트를 상품별로 집계한 뒤 버전과 Outbox는 한 번만 기록한다")
    void viewBatchCreatesOneJobPerProductAndBatch() {
        viewService.recordProductView(userId, productId);
        jdbc.update("UPDATE product_views SET last_viewed_at=TIMESTAMPADD(HOUR,-25,NOW(6)) WHERE product_id=?",
                productId);
        viewService.recordProductView(userId, productId);
        assertThat(versions()).isEmpty();

        assertThat(viewBatchService.processPendingEvents()).isEqualTo(2);
        assertThat(value("views")).isEqualTo(2);
        assertThat(value("search_version")).isEqualTo(1);
        assertThat(versions()).containsExactly(1L);

        assertThat(viewBatchService.processPendingEvents()).isZero();
        assertThat(versions()).containsExactly(1L);
    }

    @Test
    @DisplayName("Writer는 기존 트랜잭션 밖에서 호출할 수 없다")
    void writerRequiresExistingTransaction() {
        assertThatThrownBy(() -> writer.enqueue(productId))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(versions()).isEmpty();
    }

    @Test
    @DisplayName("없는 상품의 작업 저장은 예외를 발생시킨다")
    void missingProductIsNotSilentlyIgnored() {
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        assertThatThrownBy(() -> transaction().executeWithoutResult(tx -> writer.enqueue(productId)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("동기화할 상품이 존재하지 않습니다.");
        assertThat(versions()).isEmpty();
    }

    @Test
    @DisplayName("동일 상품 버전의 중복 기록은 DB 제약으로 차단한다")
    void duplicateVersionIsRejected() {
        stockService.deductStockIfAvailable(productId, 1);
        assertThatThrownBy(() -> transaction().executeWithoutResult(tx -> writer.enqueue(productId)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(versions()).containsExactly(1L);
    }

    @Test
    @DisplayName("상품 물리 삭제 후에도 작업 기록은 보존된다")
    void physicalRemovalDoesNotEraseRecoveryRecord() {
        stockService.deductStockIfAvailable(productId, 1);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        assertThat(versions()).containsExactly(1L);
    }

    @Test
    @DisplayName("동시 상품 변경도 성공 횟수만큼 고유 버전과 작업을 기록한다")
    void concurrentChangesKeepUniqueProductVersions() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Callable<Boolean>> requests = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                requests.add(() -> stockService.deductStockIfAvailable(productId, 1));
            }
            for (Future<Boolean> result : executor.invokeAll(requests, 30, TimeUnit.SECONDS)) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isTrue();
            }
        }
        assertThat(value("quantity")).isEqualTo(88);
        assertThat(value("sales")).isEqualTo(12);
        assertThat(value("search_version")).isEqualTo(12);
        assertThat(versions()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private long value(String column) {
        if ("views".equals(column)) {
            return jdbc.queryForObject(
                    "SELECT views FROM product_view_stats WHERE product_id = ?", Long.class, productId);
        }
        return jdbc.queryForObject("SELECT " + column + " FROM products WHERE id = ?", Long.class, productId);
    }

    private List<Long> versions() {
        return jdbc.queryForList("""
                SELECT product_version FROM product_search_outbox WHERE product_id = ? ORDER BY product_version
                """, Long.class, productId);
    }
}
