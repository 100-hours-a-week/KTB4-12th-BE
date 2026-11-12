package com.gift.gift.domain.order.recovery;

import java.time.Duration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThatCode;

class PaymentRecoveryTaskMigrationServiceTest {

    @Test
    @DisplayName("기존 상태 이관 SQL은 실제 MySQL에서 오류 없이 실행되고 반복 실행해도 안전하다")
    void migrateLegacyTasksRunsOnMySqlAndIsIdempotent() {
        try (var mysql = new MySQLContainer("mysql:8.4")
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
            var service = new PaymentRecoveryTaskMigrationService(
                    jdbc, Duration.ofMinutes(10), Duration.ofMinutes(1));

            assertThatCode(() -> {
                service.migrateLegacyTasks();
                service.migrateLegacyTasks();
            }).doesNotThrowAnyException();
        }
    }
}
