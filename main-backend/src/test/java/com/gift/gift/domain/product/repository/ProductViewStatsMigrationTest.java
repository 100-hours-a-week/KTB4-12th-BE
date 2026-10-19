package com.gift.gift.domain.product.repository;

import java.sql.Connection;
import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class ProductViewStatsMigrationTest {

    @Test
    @DisplayName("통계 이관은 기존 조회수·삭제 상태·시각을 보존하고 새 상품의 통계를 생성한다")
    void migrate_preservesExistingStatsAndCreatesStatsForNewProducts() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer("mysql:8.4")
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration").target("31").load().migrate();
            try (Connection connection = DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                jdbc.update("""
                        INSERT INTO categories (id, name, created_at, updated_at)
                        VALUES (1, '이관 테스트', NOW(6), NOW(6))
                        """);
                jdbc.update("""
                        INSERT INTO products (
                            id, category_id, name, brand, price, quantity, views, sales,
                            deleted_at, created_at, updated_at
                        ) VALUES
                            (1, 1, '활성 상품', '브랜드', 1000, 1, 7, 0,
                             NULL, '2026-09-01', '2026-09-02'),
                            (2, 1, '삭제 상품', '브랜드', 1000, 1, 11, 0,
                             '2026-09-03', '2026-09-01', '2026-09-03')
                        """);

                Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                        .locations("classpath:db/migration").target("32").load().migrate();

                assertThat(jdbc.queryForList("SELECT views FROM product_view_stats ORDER BY product_id",
                        Integer.class)).containsExactly(7, 11);
                assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM product_view_stats s JOIN products p ON p.id = s.product_id
                        WHERE s.views = p.views AND s.deleted_at <=> p.deleted_at
                          AND s.created_at = p.created_at AND s.updated_at = p.updated_at
                        """, Integer.class)).isEqualTo(2);
                jdbc.update("""
                        INSERT INTO products (
                            category_id, name, brand, price, quantity, views, sales, created_at, updated_at
                        ) VALUES (1, '신규 상품', '브랜드', 1000, 1, 3, 0, NOW(6), NOW(6))
                        """);
                assertThat(jdbc.queryForList("SELECT views FROM product_view_stats ORDER BY product_id",
                        Integer.class)).containsExactly(7, 11, 3);
            }
        }
    }
}
