package com.gift.gift.domain.friend.repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class FriendshipMigrationTest {

    @Test
    @DisplayName("V17은 활성 사용자 쌍만 병합하고 최초·최종 시각과 원본 테이블을 보존한다")
    void migrate_mergesActivePairsAndPreservesSource() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer("mysql:8.4")) {
            mysql.start();
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration").target("16").load().migrate();
            try (Connection connection = DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                for (int id = 1; id <= 5; id++) {
                    jdbc.update("""
                            INSERT INTO users(id, email, password, name, birth, status, created_at, updated_at)
                            VALUES (?, ?, 'migration-test', '김친구', '2000-01-01', 'ACTIVE', NOW(6), NOW(6))
                            """, id, "migration" + id + "@example.com");
                }
                jdbc.update("""
                        INSERT INTO friends(user_id, friend_user_id, created_at, updated_at, deleted_at) VALUES
                        (1, 2, '2026-09-01', '2026-09-03', NULL),
                        (2, 1, '2026-09-02', '2026-09-05', NULL),
                        (3, 1, '2026-09-04', '2026-09-06', NULL),
                        (1, 4, '2026-09-07', '2026-09-08', '2026-09-08'),
                        (4, 1, '2026-09-07', '2026-09-09', NULL),
                        (1, 5, '2026-09-07', '2026-09-08', '2026-09-08')
                        """);

                Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                        .locations("classpath:db/migration").load().migrate();

                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM friendships", Long.class)).isEqualTo(3);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM friendships WHERE user_id_1 >= user_id_2", Long.class))
                        .isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM friendships WHERE user_id_2 = 5", Long.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM friend_requests", Long.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM friends", Long.class)).isEqualTo(6);
                assertThat(jdbc.queryForObject("SELECT created_at FROM friendships WHERE user_id_1=1 AND user_id_2=2",
                        Timestamp.class).toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
                assertThat(jdbc.queryForObject("SELECT updated_at FROM friendships WHERE user_id_1=1 AND user_id_2=2",
                        Timestamp.class).toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 9, 5, 0, 0));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM friendships WHERE deleted_at IS NOT NULL", Long.class))
                        .isZero();
            }
        }
    }
}
