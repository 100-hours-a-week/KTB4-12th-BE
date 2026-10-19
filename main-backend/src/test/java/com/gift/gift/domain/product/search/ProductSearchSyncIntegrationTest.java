package com.gift.gift.domain.product.search;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.product.search.ProductSearchOutboxRepository.Job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"app.product-search.sync.enabled=false", "app.scheduling.enabled=false"})
class ProductSearchSyncIntegrationTest {
    @Autowired private ProductSearchOutboxRepository outbox;
    @Autowired private ProductSearchSyncSourceRepository source;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    private final ProductSearchSyncProperties settings = new ProductSearchSyncProperties(1, 60, 2, 1, 10, 5000);
    private long categoryId;
    private long productId;

    @BeforeEach
    void setUp() {
        String name = "sync-" + UUID.randomUUID();
        jdbc.update("INSERT INTO categories(name,created_at,updated_at) VALUES (?,NOW(6),NOW(6))", name);
        categoryId = jdbc.queryForObject("SELECT id FROM categories WHERE name=?", Long.class, name);
        jdbc.update("""
                INSERT INTO products(category_id,name,brand,price,quantity,views,sales,
                    created_at,updated_at,search_version)
                VALUES (?,'세트','브랜드',1000,10,2,1,'2026-01-01 12:00:00.123456',NOW(6),2)
                """, categoryId);
        productId = jdbc.queryForObject("SELECT id FROM products WHERE category_id=?", Long.class, categoryId);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM product_search_outbox WHERE product_id=?", productId);
        jdbc.update("DELETE FROM products WHERE id=?", productId);
        jdbc.update("DELETE FROM categories WHERE id=?", categoryId);
    }

    @Test
    @DisplayName("임대 만료 작업을 회수하며 이전 작업자의 완료는 새 선점을 덮어쓰지 못한다")
    void recoversLeaseAndFencesOldWorker() {
        insert(1);
        List<Job> old = outbox.claim(settings);
        jdbc.update("UPDATE product_search_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE id=?", old.getFirst().id());
        outbox.complete(old);
        outbox.fail(old, false, "LateFailure", settings);
        assertThat(state()).isEqualTo("PROCESSING");
        assertThat(outbox.claim(settings)).isEmpty();
        assertThat(state()).isEqualTo("PENDING");
        makeDue();
        List<Job> renewed = outbox.claim(settings);
        assertThat(renewed.getFirst().claimToken()).isNotEqualTo(old.getFirst().claimToken());
        outbox.complete(old);
        outbox.fail(old, false, "OldOwner", settings);
        assertThat(jdbc.queryForObject("SELECT failure_count FROM product_search_outbox WHERE product_id=?",
                Integer.class, productId)).isEqualTo(1);
        assertThat(state()).isEqualTo("PROCESSING");
        outbox.complete(renewed);
        assertThat(state()).isEqualTo("PROCESSED");
    }

    @Test
    @DisplayName("재시도 한도 초과와 영구 오류는 FAILED 상태로 남긴다")
    void stopsAfterRetryLimitAndPermanentError() {
        insert(1);
        outbox.fail(outbox.claim(settings), true, "Unavailable", settings);
        assertThat(state()).isEqualTo("PENDING");
        makeDue();
        outbox.fail(outbox.claim(settings), true, "Unavailable", settings);
        assertThat(state()).isEqualTo("FAILED");
        jdbc.update("DELETE FROM product_search_outbox WHERE product_id=?", productId);
        insert(2);
        outbox.fail(outbox.claim(settings), false, "MappingError", settings);
        assertThat(state()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("동시에 실행한 두 작업자가 동일 작업을 중복 선점하지 않는다")
    void claimsWithoutOverlap() throws Exception {
        insert(1);
        insert(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<List<Job>> claim = () -> outbox.claim(settings);
            var futures = executor.invokeAll(List.of(claim, claim));
            List<Job> first = futures.getFirst().get();
            List<Job> second = futures.getLast().get();
            assertThat(first).hasSize(1);
            assertThat(second).hasSize(1);
            assertThat(first.getFirst().id()).isNotEqualTo(second.getFirst().id());
        }
    }

    @Test
    @DisplayName("원본 조회는 마이크로초 시간과 삭제 상태를 유지한다")
    void readsLatestSourceAndSoftDelete() {
        var active = source.findLatest(List.of(productId)).get(productId);
        assertThat(active.source().get("created_at")).isEqualTo("2026-01-01T03:00:00.123456Z");
        assertThat(active.version()).isEqualTo(2);
        jdbc.update("UPDATE product_view_stats SET views=8 WHERE product_id=?", productId);
        assertThat(source.findLatest(List.of(productId)).get(productId).source().get("views")).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT views FROM products WHERE id=?", Integer.class, productId)).isEqualTo(2);
        jdbc.update("UPDATE products SET deleted_at=NOW(6),search_version=3 WHERE id=?", productId);
        var deleted = source.findLatest(List.of(productId)).get(productId);
        assertThat(deleted.version()).isEqualTo(3);
        assertThat(deleted.source().get("deleted_at")).isNotNull();
        assertThat(deleted.source()).doesNotContainKey("name");
    }

    @Test
    @DisplayName("조회수 통계 누락을 상품의 물리 삭제로 오판하지 않는다")
    void refusesMissingStatsForActiveProduct() {
        jdbc.update("DELETE FROM product_view_stats WHERE product_id=?", productId);
        assertThatThrownBy(() -> source.findLatest(List.of(productId)))
                .hasMessageContaining("조회수 통계 행이 없습니다.");
    }

    @Test
    @DisplayName("다른 트랜잭션이 잠근 작업은 기다리지 않고 다음 작업을 선점한다")
    void skipsRowLockedByAnotherTransaction() throws Exception {
        insert(1);
        insert(2);
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("""
                        SELECT id FROM product_search_outbox
                        WHERE product_id=? AND product_version=1 FOR UPDATE
                        """, Long.class, productId);
                Future<List<Job>> claimed = executor.submit(() -> outbox.claim(settings));
                try {
                    assertThat(claimed.get(10, TimeUnit.SECONDS))
                            .extracting(Job::productVersion).containsExactly(2L);
                } catch (Exception exception) {
                    throw new IllegalStateException("잠긴 작업을 건너뛰지 못했습니다.", exception);
                }
            });
        }
        assertThat(outbox.claim(settings)).extracting(Job::productVersion).containsExactly(1L);
    }

    @Test
    @DisplayName("완료 상태는 호출자 트랜잭션이 롤백되어도 독립적으로 커밋된다")
    void completeCommitsIndependentlyOfCallerRollback() {
        insert(1);
        List<Job> jobs = outbox.claim(settings);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outbox.complete(jobs);
            throw new IllegalStateException("호출자 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(state()).isEqualTo("PROCESSED");
    }

    @Test
    @DisplayName("재시도 상태는 호출자 트랜잭션이 롤백되어도 독립적으로 커밋된다")
    void failureCommitsIndependentlyOfCallerRollback() {
        insert(1);
        List<Job> jobs = outbox.claim(settings);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outbox.fail(jobs, true, "Unavailable", settings);
            throw new IllegalStateException("호출자 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(state()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT failure_count FROM product_search_outbox WHERE product_id=?",
                Integer.class, productId)).isEqualTo(1);
    }

    @Test
    @DisplayName("재시도 간격은 지수적으로 증가하되 설정한 최대 간격을 넘지 않는다")
    void capsRetryBackoff() {
        ProductSearchSyncProperties retrySettings = new ProductSearchSyncProperties(1, 60, 5, 2, 3, 5000);
        insert(1);
        outbox.fail(outbox.claim(retrySettings), true, "Unavailable", retrySettings);
        assertThat(retryDelay()).isEqualTo(2);
        makeDue();
        outbox.fail(outbox.claim(retrySettings), true, "Unavailable", retrySettings);
        assertThat(retryDelay()).isEqualTo(3);
    }

    @Test
    @DisplayName("작업자 중단으로 임대가 반복 만료되어도 실패 한도에 도달하면 보존하고 중단한다")
    void leaseExpiryAlsoHonorsFailureLimit() {
        ProductSearchSyncProperties oneFailure = new ProductSearchSyncProperties(1, 60, 1, 1, 10, 5000);
        insert(1);
        List<Job> jobs = outbox.claim(oneFailure);
        jdbc.update("UPDATE product_search_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE id=?",
                jobs.getFirst().id());

        assertThat(outbox.claim(oneFailure)).isEmpty();
        assertThat(state()).isEqualTo("FAILED");
        assertThat(outbox.claim(oneFailure)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT failure_count FROM product_search_outbox WHERE product_id=?",
                Integer.class, productId)).isEqualTo(1);
    }

    private long retryDelay() {
        return jdbc.queryForObject("""
                SELECT TIMESTAMPDIFF(SECOND, updated_at, next_attempt_at)
                FROM product_search_outbox WHERE product_id=?
                """, Long.class, productId);
    }

    private void insert(long version) {
        jdbc.update("INSERT INTO product_search_outbox(product_id,product_version) VALUES (?,?)", productId, version);
    }
    private void makeDue() {
        jdbc.update("UPDATE product_search_outbox SET next_attempt_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE product_id=?", productId);
    }
    private String state() {
        return jdbc.queryForObject("SELECT status FROM product_search_outbox WHERE product_id=?", String.class, productId);
    }
}
