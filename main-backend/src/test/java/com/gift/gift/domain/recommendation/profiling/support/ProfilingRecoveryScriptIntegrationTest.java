package com.gift.gift.domain.recommendation.profiling.support;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.recommendation.profiling.dto.request.AiProfileRequest;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingRequestMessage;

import static org.assertj.core.api.Assertions.*;

class ProfilingRecoveryScriptIntegrationTest {
    @TempDir Path temporary;
    private int execution;

    @Test
    @DisplayName("실제 운영 스크립트가 격리 DB에서 조회·동일 입력 재개·중복 거부·최신 입력 대체를 처리한다")
    void executesRealScriptAgainstIsolatedDatabase() throws Exception {
        try (var mysql = new MySQLContainer("mysql:8.4").withCommand("--log-bin-trust-function-creators=1");
             var redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379)) {
            mysql.start();
            redis.start();
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration").load().migrate();
            try (Connection connection = DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                String first = seed(jdbc, 1);
                String second = seed(jdbc, 2);
                Map<String, String> environment = environment(mysql, redis);
                var listed = execute(environment, Map.of("PROFILING_RECOVERY_ACTION", "LIST"));
                assertThat(listed.exit()).as(listed.output()).isZero();
                assertThat(listed.output()).contains("requestId\tprofileId", first, second)
                        .doesNotContain("private-original-input", "private-latest-input", "Attempting to connect to:");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profiling_jobs WHERE publish_status = 'BLOCKED'",
                        Integer.class)).isEqualTo(2);

                var original = jdbc.queryForMap("SELECT * FROM profiling_jobs WHERE request_id = ?", first);
                var resumeCommand = command(jdbc, first, "RESUME_SAME");
                var resumed = execute(environment, resumeCommand);
                assertThat(resumed.exit()).as(resumed.output()).isZero();
                assertThat(resumed.output()).contains("RECOVERY_OK", "PROFILING_RECOVERY_COMMITTED", "previousAttempts=3")
                        .doesNotContain("private-original-input", "private-latest-input");
                var after = jdbc.queryForMap("SELECT * FROM profiling_jobs WHERE request_id = ?", first);
                assertThat(after.get("request_body")).isEqualTo(original.get("request_body"));
                assertThat(after.get("source_version")).isEqualTo(1L);
                assertThat(after.get("publish_status")).isEqualTo("READY");
                assertThat(after.get("attempt_count")).isEqualTo(0);
                assertThat(after.get("first_failed_at")).isNull();
                assertThat(after.get("next_attempt_at")).isNull();
                assertThat(after.get("claim_token")).isNull();
                assertThat(after.get("published_at")).isNull();

                var duplicate = execute(environment, resumeCommand);
                assertThat(duplicate.exit()).as(duplicate.output()).isNotZero();
                assertThat(duplicate.output()).contains("RECOVERY_FAILED").doesNotContain("PROFILING_RECOVERY_COMMITTED");
                assertThat(jdbc.queryForMap("SELECT * FROM profiling_jobs WHERE request_id = ?", first)).isEqualTo(after);

                var replaced = execute(environment, command(jdbc, second, "REPLACE_LATEST"));
                assertThat(replaced.exit()).as(replaced.output()).isZero();
                assertThat(replaced.output()).contains("RECOVERY_OK", "PROFILING_RECOVERY_COMMITTED", "nextVersion=2")
                        .doesNotContain("private-original-input", "private-latest-input");
                var old = jdbc.queryForMap("SELECT * FROM profiling_jobs WHERE request_id = ?", second);
                assertThat(old.get("result_status")).isEqualTo("SUPERSEDED");
                assertThat(old.get("finished_at")).isNotNull();
                var next = jdbc.queryForMap("SELECT * FROM profiling_jobs WHERE profile_id = 2 AND source_version = 2");
                assertThat(next.get("request_id")).isNotEqualTo(second);
                assertThat(next.get("request_body").toString()).contains("private-latest-input");
                assertThat(next.get("publish_status")).isEqualTo("READY");
                assertThat(next.get("published_at")).isNull();
                assertThat(jdbc.queryForObject("SELECT source_version FROM recipient_profiles WHERE id = 2", Long.class))
                        .isEqualTo(2L);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profiling_jobs", Integer.class)).isEqualTo(3);
            }
        }
    }

    private String seed(JdbcTemplate jdbc, long id) {
        jdbc.update("""
                INSERT INTO users (id, email, password, name, birth, status, created_at, updated_at)
                VALUES (?, ?, 'test-hash', '운영 명령 테스트', '1990-01-01', 'ACTIVE', NOW(6), NOW(6))
                """, id, "script-" + id + "@example.invalid");
        jdbc.update("""
                INSERT INTO recipient_profiles (id, recipient_id, profile_status, source_version,
                    analyzed_source_version, pending_since, last_changed_at, window_started_at, created_at, updated_at)
                VALUES (?, ?, 'PENDING', 1, 0, NOW(6), NOW(6), NOW(6), NOW(6), NOW(6))
                """, id, id);
        jdbc.update("""
                INSERT INTO user_gift_preferences (user_id, preference, created_at, updated_at)
                VALUES (?, 'private-latest-input', NOW(6), NOW(6))
                """, id);
        var message = ProfilingRequestMessage.create(UUID.randomUUID(), Instant.now(),
                new AiProfileRequest(id, 1, List.of(), "private-original-input", List.of()));
        var body = new ProfilingMessageSerializer(new ObjectMapper()).serialize(message).json();
        jdbc.update("""
                INSERT INTO profiling_jobs (request_id, profile_id, source_version, request_body, publish_status,
                    result_status, attempt_count, first_failed_at, next_attempt_at, last_error_code, created_at, updated_at)
                VALUES (?, ?, 1, ?, 'BLOCKED', 'WAITING', 3, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
                    'PUBLISH_BUDGET_EXHAUSTED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, message.requestId(), id, body);
        return message.requestId();
    }

    private Map<String, String> environment(MySQLContainer mysql, GenericContainer<?> redis) {
        var environment = new HashMap<String, String>();
        // 사용자 .env·인증 정보는 상속하지 않고 테스트 컨테이너 주소만 전달한다.
        environment.put("PATH", System.getenv("PATH"));
        environment.put("HOME", System.getProperty("user.home"));
        environment.put("JAVA_HOME", System.getProperty("java.home"));
        environment.put("GRADLE_OPTS", "-Dorg.gradle.workers.max=1");
        environment.put("SPRING_CONFIG_LOCATION", Path.of("src/test/resources/application.yaml").toAbsolutePath().toUri()
                + "," + Path.of("src/test/resources/application.properties").toAbsolutePath().toUri());
        environment.put("SPRING_DATASOURCE_URL", mysql.getJdbcUrl());
        environment.put("SPRING_DATASOURCE_USERNAME", mysql.getUsername());
        environment.put("SPRING_DATASOURCE_PASSWORD", mysql.getPassword());
        environment.put("REDIS_TEST_HOST", redis.getHost());
        environment.put("REDIS_TEST_PORT", redis.getMappedPort(6379).toString());
        // 브로커가 없는 주소에서도 운영 명령은 DB 변경까지만 수행해야 한다.
        environment.put("SPRING_RABBITMQ_HOST", "127.0.0.1");
        environment.put("SPRING_RABBITMQ_PORT", "1");
        // 서버 환경에서 수신을 켰어도 일회성 DB 복구 명령은 Consumer를 실행하지 않아야 한다.
        environment.put("SPRING_APPLICATION_JSON", "{\"app\":{\"ai-profile\":{\"result-consumer\":{\"enabled\":true}}}}");
        return environment;
    }

    private Map<String, String> command(JdbcTemplate jdbc, String requestId, String action) {
        var row = jdbc.queryForMap("SELECT * FROM profiling_jobs WHERE request_id = ?", requestId);
        var updated = jdbc.queryForObject("SELECT updated_at FROM profiling_jobs WHERE request_id = ?",
                LocalDateTime.class, requestId);
        return Map.of("PROFILING_RECOVERY_ACTION", action,
                "PROFILING_RECOVERY_PROFILE_ID", row.get("profile_id").toString(),
                "PROFILING_RECOVERY_REQUEST_ID", requestId,
                "PROFILING_RECOVERY_SOURCE_VERSION", row.get("source_version").toString(),
                "PROFILING_RECOVERY_UPDATED_AT", updated.toString(),
                "PROFILING_RECOVERY_OPERATOR", "test-operator",
                "PROFILING_RECOVERY_REASON", "script verification");
    }

    private Execution execute(Map<String, String> environment, Map<String, String> command) throws Exception {
        Path script = Path.of("../scripts/profiling/profiling-recovery.sh").toRealPath();
        Path log = temporary.resolve("script-" + (++execution) + ".log");
        var builder = new ProcessBuilder("bash", script.toString()).directory(temporary.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        builder.environment().putAll(command);
        Process process = builder.start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("운영 스크립트 제한 시간 초과: " + Files.readString(log));
        }
        return new Execution(process.exitValue(), Files.readString(log));
    }

    private record Execution(int exit, String output) { }
}
