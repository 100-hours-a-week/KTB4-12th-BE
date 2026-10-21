package com.gift.gift.domain.product.search;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.gift.gift.domain.product.search.ProductSearchSyncSourceRepository.Snapshot;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.product-search.sync.enabled=false",
        "app.scheduling.enabled=false",
        "spring.elasticsearch.uris=${ELASTICSEARCH_URIS:http://127.0.0.1:9201}"
})
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_ES_SYNC_TEST", matches = "true")
class ProductSearchSyncElasticsearchTest {
    @Autowired private ElasticsearchClient client;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductSearchOutboxRepository outbox;
    @Autowired private ProductSearchSyncSourceRepository source;
    private String index;
    private ProductSearchBulkWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        index = "test-product-sync-" + UUID.randomUUID();
        try (InputStream mapping = getClass().getResourceAsStream("/elasticsearch/products-index.json")) {
            client.indices().create(c -> c.index(index).withJson(mapping));
        }
        try (InputStream mapping = getClass().getResourceAsStream("/elasticsearch/products-sync-mapping.json")) {
            client.indices().putMapping(m -> m.index(index).withJson(mapping));
        }
        writer = new ProductSearchBulkWriter(client, index);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (index != null) {
            client.indices().delete(d -> d.index(index));
        }
    }

    @Test
    @DisplayName("중복·역순 갱신은 무시하고 삭제 표시 이후 과거 상품이 복원되지 않는다")
    void keepsLatestVersionAndTombstone() throws Exception {
        client.index(i -> i.index(index).id("1").document(Map.of("id", 1L, "views", 0)));
        assertSuccess(new Snapshot(1, 2, Map.of("id", 1L, "views", 2, "search_version", 2L)));
        assertSuccess(new Snapshot(1, 1, Map.of("id", 1L, "views", 1, "search_version", 1L)));
        assertSuccess(new Snapshot(1, 2, Map.of("id", 1L, "views", 99, "search_version", 2L)));
        Map<?, ?> current = client.get(g -> g.index(index).id("1"), Map.class).source();
        assertThat(((Number) current.get("views")).intValue()).isEqualTo(2);
        assertSuccess(new Snapshot(1, 3, Map.of("id", 1L, "search_version", 3L,
                "deleted_at", "2026-01-01T00:00:00Z")));
        assertSuccess(new Snapshot(1, 2, Map.of("id", 1L, "views", 2, "search_version", 2L)));
        client.indices().refresh(r -> r.index(index));
        assertThat(client.count(c -> c.index(index).query(q -> q.bool(v -> v
                .mustNot(n -> n.exists(e -> e.field("deleted_at")))))).count()).isZero();
        assertSuccess(Snapshot.physicallyDeleted(1));
        assertSuccess(new Snapshot(1, 4, Map.of("id", 1L, "views", 4, "search_version", 4L)));
        Map<?, ?> deleted = client.get(g -> g.index(index).id("1"), Map.class).source();
        assertThat(((Number) deleted.get("search_version")).longValue()).isEqualTo(Long.MAX_VALUE);
        assertThat(deleted.get("deleted_at")).isNotNull();
    }

    @Test
    @DisplayName("MySQL 최신 상품을 실제 매핑으로 Bulk 반영하고 병합한 작업을 완료한다")
    void synchronizesLatestMysqlSnapshot() throws Exception {
        String name = "sync-es-" + UUID.randomUUID();
        jdbc.update("INSERT INTO categories(name,created_at,updated_at) VALUES (?,NOW(6),NOW(6))", name);
        long categoryId = jdbc.queryForObject("SELECT id FROM categories WHERE name=?", Long.class, name);
        Long productId = null;
        try {
            jdbc.update("""
                    INSERT INTO products(category_id,name,brand,price,quantity,views,sales,
                        created_at,updated_at,search_version)
                    VALUES (?,'선물세트','브랜드',1000,10,2,1,NOW(6),NOW(6),2)
                    """, categoryId);
            productId = jdbc.queryForObject("SELECT id FROM products WHERE category_id=?", Long.class, categoryId);
            jdbc.update("UPDATE product_view_stats SET views=8 WHERE product_id=?", productId);
            jdbc.update("INSERT INTO product_search_outbox(product_id,product_version) VALUES (?,1),(?,2)", productId, productId);
            new ProductSearchSyncService(outbox, source, writer,
                    new ProductSearchSyncProperties(100, 60, 3, 2, 30, 5000)).syncOnce();
            assertThat(jdbc.queryForList("SELECT status FROM product_search_outbox WHERE product_id=?",
                    String.class, productId)).containsExactly("PROCESSED", "PROCESSED");
            String id = productId.toString();
            Map<?, ?> indexed = client.get(g -> g.index(index).id(id), Map.class).source();
            assertThat(indexed.get("name")).isEqualTo("선물세트");
            assertThat(((Number) indexed.get("views")).intValue()).isEqualTo(8);
            assertThat(((Number) indexed.get("search_version")).longValue()).isEqualTo(2);
        } finally {
            if (productId != null) {
                jdbc.update("DELETE FROM product_search_outbox WHERE product_id=?", productId);
                jdbc.update("DELETE FROM products WHERE id=?", productId);
            }
            jdbc.update("DELETE FROM categories WHERE id=?", categoryId);
        }
    }

    @Test
    @DisplayName("실제 Bulk의 일부 문서만 매핑 오류가 나면 성공한 항목과 영구 오류를 구분한다")
    void reportsRealPartialMappingFailure() throws Exception {
        List<ProductSearchBulkWriter.Result> results = writer.write(List.of(
                new Snapshot(1, 1, Map.of("id", 1L, "views", 1, "search_version", 1L)),
                new Snapshot(2, 1, Map.of("id", 2L, "views", "invalid-number", "search_version", 1L))));

        assertThat(results.getFirst().success()).isTrue();
        assertThat(results.getLast().success()).isFalse();
        assertThat(results.getLast().retryable()).isFalse();
        assertThat(client.exists(e -> e.index(index).id("1")).value()).isTrue();
        assertThat(client.exists(e -> e.index(index).id("2")).value()).isFalse();
    }

    @Test
    @DisplayName("소프트 삭제 후 더 높은 버전으로 복구하면 삭제 표시를 제거한다")
    void restoresSoftDeletedDocumentWithNewerVersion() throws Exception {
        assertSuccess(new Snapshot(1, 3, Map.of("id", 1L, "search_version", 3L,
                "deleted_at", "2026-01-01T00:00:00Z")));
        assertSuccess(new Snapshot(1, 4, Map.of("id", 1L, "views", 4, "search_version", 4L)));

        Map<?, ?> restored = client.get(g -> g.index(index).id("1"), Map.class).source();
        assertThat(restored.get("deleted_at")).isNull();
        assertThat(((Number) restored.get("views")).intValue()).isEqualTo(4);
    }

    private void assertSuccess(Snapshot snapshot) throws Exception {
        assertThat(writer.write(List.of(snapshot))).allMatch(ProductSearchBulkWriter.Result::success);
    }
}
