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

class ProductViewLegacyRemovalMigrationTest {

    @Test
    @DisplayName("레거시 조회 저장소 삭제 후에도 기존 조회수 통계를 유지하고 새 상품의 통계를 0으로 만든다")
    void migrate_dropsLegacyStorageAndKeepsViewStats() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer("mysql:8.4")
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            migrate(mysql, "54");
            try (Connection connection = DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                jdbc.update("""
                        INSERT INTO categories (id, name, created_at, updated_at)
                        VALUES (1, '삭제 테스트', NOW(6), NOW(6))
                        """);
                jdbc.update("""
                        INSERT INTO products (
                            id, category_id, name, brand, price, quantity, views, sales, created_at, updated_at
                        ) VALUES (1, 1, '기존 상품', '브랜드', 1000, 1, 7, 0, NOW(6), NOW(6))
                        """);

                migrate(mysql, "55");

                assertThat(countSchemaObjects(jdbc, """
                        SELECT COUNT(*) FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND table_name = 'products' AND column_name = 'views'
                        """)).isZero();
                assertThat(countSchemaObjects(jdbc, """
                        SELECT COUNT(*) FROM information_schema.tables
                        WHERE table_schema = DATABASE()
                          AND table_name IN ('product_views', 'product_view_events')
                        """)).isZero();
                assertThat(jdbc.queryForObject(
                        "SELECT views FROM product_view_stats WHERE product_id = 1", Integer.class)).isEqualTo(7);

                jdbc.update("""
                        INSERT INTO products (
                            id, category_id, name, brand, price, quantity, sales, created_at, updated_at
                        ) VALUES (2, 1, '신규 상품', '브랜드', 1000, 1, 0, NOW(6), NOW(6))
                        """);
                assertThat(jdbc.queryForObject(
                        "SELECT views FROM product_view_stats WHERE product_id = 2", Integer.class)).isZero();
            }
        }
    }

    private void migrate(MySQLContainer mysql, String target) {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration").target(target).load().migrate();
    }

    private int countSchemaObjects(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
