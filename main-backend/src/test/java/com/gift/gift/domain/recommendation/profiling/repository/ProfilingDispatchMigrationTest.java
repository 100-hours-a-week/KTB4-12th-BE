package com.gift.gift.domain.recommendation.profiling.repository;

import java.sql.Connection;
import java.sql.DriverManager;
import db.migration.V51__remove_legacy_profile_dispatch_fields;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfilingDispatchMigrationTest {
    @Test
    @DisplayName("기존 HTTP 진행 작업은 제거 전에 거부하고 완료 프로필·추천·MQ 작업을 보존한다")
    void guardsLegacyRequestsAndPreservesProfilesRecommendationsAndMqJobs() throws Exception {
        try (var mysql = new MySQLContainer("mysql:8.4")
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration").target("50").load().migrate();
            try (Connection connection = DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                jdbc.update("""
                        INSERT INTO users (id, email, password, name, birth, status, created_at, updated_at)
                        VALUES (1, 'complete@test.invalid', 'hash', '완료', '1990-01-01', 'ACTIVE', NOW(6), NOW(6)),
                               (2, 'pending@test.invalid', 'hash', '대기', '1990-01-01', 'ACTIVE', NOW(6), NOW(6))
                        """);
                jdbc.update("""
                        INSERT INTO recipient_profiles (id, recipient_id, profile_status, source_version,
                            analyzed_source_version, pending_since, created_at, updated_at)
                        VALUES (1, 1, 'COMPLETED', 46, 46, NULL, NOW(6), NOW(6)),
                               (2, 2, 'PENDING', 1, 0, NOW(6), NOW(6), NOW(6))
                        """);
                jdbc.update("INSERT INTO categories (id, name, created_at, updated_at) VALUES (1, '카테고리', NOW(6), NOW(6))");
                jdbc.update("""
                        INSERT INTO products (id, category_id, name, brand, price, quantity, views, sales,
                            created_at, updated_at) VALUES (1, 1, '상품', '브랜드', 1000, 1, 0, 0, NOW(6), NOW(6))
                        """);
                jdbc.update("""
                        INSERT INTO recipient_recommended_products (recipient_id, product_id, rank_order,
                            source_version, created_at, updated_at) VALUES (1, 1, 1, 46, NOW(6), NOW(6))
                        """);
                var migration = new V51__remove_legacy_profile_dispatch_fields();
                Context context = mock(Context.class);
                when(context.getConnection()).thenReturn(connection);
                assertThatThrownBy(() -> migration.migrate(context))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Legacy HTTP");
                assertThat(jdbc.queryForObject("SELECT retry_count FROM recipient_profiles WHERE id = 1",
                        Integer.class)).isZero();

                jdbc.update("""
                        INSERT INTO profiling_jobs (request_id, profile_id, source_version, request_body,
                            publish_status, result_status, created_at, updated_at)
                        VALUES ('test-request', 2, 1, '{"snapshot":true}', 'READY', 'WAITING', NOW(6), NOW(6))
                        """);
                jdbc.update("""
                        UPDATE recipient_profiles SET dispatch_claim_token = 'legacy-claim',
                            dispatch_claimed_at = NOW(6) WHERE id = 2
                        """);
                assertThatThrownBy(() -> migration.migrate(context)).isInstanceOf(IllegalStateException.class);
                jdbc.update("""
                        UPDATE recipient_profiles SET dispatch_claim_token = NULL, dispatch_claimed_at = NULL
                        WHERE id = 2
                        """);
                String profileQuery = """
                        SELECT id, recipient_id, profile_status, source_version, analyzed_source_version,
                            last_changed_at, window_started_at, pending_since, created_at, updated_at
                        FROM recipient_profiles ORDER BY id
                        """;
                var profiles = jdbc.queryForList(profileQuery);
                var recommendations = jdbc.queryForList("SELECT * FROM recipient_recommended_products");
                var jobs = jdbc.queryForList("SELECT * FROM profiling_jobs");
                Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                        .locations("classpath:db/migration").target("51").load().migrate();
                assertThat(jdbc.queryForList(profileQuery)).isEqualTo(profiles);
                assertThat(jdbc.queryForList("SELECT * FROM recipient_recommended_products")).isEqualTo(recommendations);
                assertThat(jdbc.queryForList("SELECT * FROM profiling_jobs")).isEqualTo(jobs);
                assertThat(jdbc.queryForList("""
                        SELECT COLUMN_NAME FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recipient_profiles'
                        """, String.class)).doesNotContain("retry_count", "dispatch_claim_token",
                        "dispatch_claimed_at", "dispatch_claim_last_changed_at");
                assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'recipient_profiles'
                          AND CONSTRAINT_NAME IN ('chk_recipient_profiles_dispatch_claim',
                            'chk_recipient_profiles_retry_count')
                        """, Integer.class)).isZero();
            }
        }
    }
}
