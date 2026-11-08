package com.gift.gift.domain.recommendation.profiling.repository;

import java.sql.SQLException;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.*;

class ProfilingResultFingerprintMigrationTest {

    @Test
    @DisplayName("V53은 기존 프로필·추천·모든 작업 상태를 보존하고 NULL 비교값만 추가한다")
    void preservesRowsAndEnforcesNullableLowercaseHexWithoutUniqueness() {
        try (var mysql = new MySQLContainer("mysql:8.4")
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            migrate(mysql, "52");
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
            seed(jdbc);
            var profiles = jdbc.queryForList("SELECT * FROM recipient_profiles");
            var recommendations = jdbc.queryForList("SELECT * FROM recipient_recommended_products");
            var jobs = jdbc.queryForList("SELECT * FROM profiling_jobs ORDER BY source_version");

            migrate(mysql, "53");

            assertThat(jdbc.queryForList("SELECT * FROM recipient_profiles")).isEqualTo(profiles);
            assertThat(jdbc.queryForList("SELECT * FROM recipient_recommended_products")).isEqualTo(recommendations);
            var migratedJobs = jdbc.queryForList("SELECT * FROM profiling_jobs ORDER BY source_version");
            migratedJobs.forEach(job -> assertThat(job.remove("result_fingerprint")).isNull());
            assertThat(migratedJobs).isEqualTo(jobs);
            assertThat(jdbc.queryForMap("""
                    SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE
                    FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'profiling_jobs'
                      AND COLUMN_NAME = 'result_fingerprint'
                    """)).containsEntry("DATA_TYPE", "char")
                    .containsEntry("CHARACTER_MAXIMUM_LENGTH", 64L).containsEntry("IS_NULLABLE", "YES");
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'profiling_jobs'
                      AND COLUMN_NAME = 'result_fingerprint'
                    """, Integer.class)).isZero();

            String hash = "a".repeat(64);
            assertThat(jdbc.update("UPDATE profiling_jobs SET result_fingerprint = ?", hash)).isEqualTo(4);
            assertThat(jdbc.queryForList("SELECT result_fingerprint FROM profiling_jobs", String.class))
                    .containsOnly(hash);
            for (String invalid : List.of("a".repeat(63), "A".repeat(64), "g".repeat(64), "")) {
                assertThatThrownBy(() -> jdbc.update(
                        "UPDATE profiling_jobs SET result_fingerprint = ? WHERE source_version = 1", invalid))
                        .isInstanceOf(DataAccessException.class)
                        .rootCause().isInstanceOfSatisfying(SQLException.class,
                                exception -> assertThat(exception.getErrorCode()).isEqualTo(3819));
            }
            assertThat(jdbc.update("UPDATE profiling_jobs SET result_fingerprint = NULL")).isEqualTo(4);
        }
    }

    private void migrate(MySQLContainer mysql, String target) {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration").target(target).load().migrate();
    }

    private void seed(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO users (id, email, password, name, birth, status, created_at, updated_at)
                VALUES (1, 'fingerprint@test.invalid', 'hash', '테스트', '1990-01-01', 'ACTIVE', NOW(6), NOW(6))
                """);
        jdbc.update("""
                INSERT INTO recipient_profiles (id, recipient_id, profile_status, source_version,
                    analyzed_source_version, pending_since, last_changed_at, window_started_at, created_at, updated_at)
                VALUES (1, 1, 'PENDING', 4, 1, NOW(6), NOW(6), NOW(6), NOW(6), NOW(6))
                """);
        jdbc.update("INSERT INTO categories (id, name, created_at, updated_at) VALUES (1, '분류', NOW(6), NOW(6))");
        jdbc.update("""
                INSERT INTO products (id, category_id, name, brand, price, quantity, views, sales, created_at, updated_at)
                VALUES (1, 1, '상품', '브랜드', 1000, 1, 0, 0, NOW(6), NOW(6))
                """);
        jdbc.update("""
                INSERT INTO recipient_recommended_products (recipient_id, product_id, rank_order,
                    source_version, created_at, updated_at) VALUES (1, 1, 1, 1, NOW(6), NOW(6))
                """);
        var statuses = List.of("COMPLETED", "FAILED", "SUPERSEDED", "WAITING");
        for (int i = 0; i < statuses.size(); i++) {
            jdbc.update("""
                    INSERT INTO profiling_jobs (request_id, profile_id, source_version, request_body,
                        publish_status, result_status, published_at, finished_at, last_error_code,
                        created_at, updated_at)
                    VALUES (?, 1, ?, '{"snapshot":"원본"}', 'PUBLISHED', ?, NOW(6), ?, ?, NOW(6), NOW(6))
                    """, "83ae0a27-0ddc-4fb0-a587-35e4937e4f7" + i, i + 1, statuses.get(i),
                    i == 3 ? null : java.sql.Timestamp.valueOf("2026-11-01 00:00:00"),
                    i == 1 ? "ANALYSIS_FAILED" : null);
        }
    }
}
