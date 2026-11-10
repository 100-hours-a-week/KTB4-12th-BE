package com.gift.gift.domain.recommendation.profiling.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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

import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.profiling.repository.ProfilingPublishStore.Claim;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "app.ai-profile.amqp.enabled=false"})
class ProfilingPublishRepositoryIntegrationTest {
    @Autowired private ProfilingPublishStore repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private RecipientProfileRepository profiles;
    @Autowired private PlatformTransactionManager transactionManager;

    private Long userId;
    private Long profileId;
    private String requestId;
    private static final String BODY = "{\"payload\":\"고정 입력\"}";

    @BeforeEach
    void setUp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User user = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                    "$2a$10$" + "a".repeat(53), "발행테스트", LocalDate.of(2000, 1, 1)));
            userId = user.getId();
            profileId = profiles.saveAndFlush(new RecipientProfile(user)).getId();
        });
        requestId = insertJob(1);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM profiling_jobs WHERE profile_id = ?", profileId);
        profiles.deleteById(profileId);
        users.deleteById(userId);
    }

    @Test
    @DisplayName("재시도 시 요청 ID·본문은 보존하고 시도 토큰만 바꾼다")
    void retry_preservesSnapshotAndChangesToken() {
        Claim first = repository.claimOne().orElseThrow();
        assertThat(first.requestId()).isEqualTo(requestId);
        assertThat(integer("attempt_count")).isEqualTo(1);
        assertThat(repository.markFailed(first, false, "PUBLISH_NACK")).isTrue();
        assertThat(text("publish_status")).isEqualTo("RETRY_WAIT");
        LocalDateTime firstFailure = time("first_failed_at");
        long delay = java.time.Duration.between(firstFailure, time("next_attempt_at")).toMillis();
        assertThat(delay).isBetween(48_000L, 72_000L);
        assertThat(repository.claimOne()).isEmpty();
        makeRetryDue();
        Claim second = repository.claimOne().orElseThrow();
        assertThat(second.requestId()).isEqualTo(first.requestId());
        assertThat(second.body()).isEqualTo(BODY);
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(integer("attempt_count")).isEqualTo(2);
        assertThat(repository.markPublished(first)).isFalse();
        assertThat(repository.markFailed(first, true, "LATE_FAILURE")).isFalse();
        assertThat(repository.releaseUnsent(first)).isFalse();
        assertThat(repository.markPublished(second)).isTrue();
        assertThat(text("publish_status")).isEqualTo("PUBLISHED");
        assertThat(time("published_at")).isNotNull();
        assertThat(text("claim_token")).isNull();
        assertThat(text("last_error_code")).isNull();
        assertThat(repository.claimOne()).isEmpty();
    }

    @Test
    @DisplayName("세 번째 발행 실패는 BLOCKED가 되고 자동 선점되지 않는다")
    void thirdFailure_blocks() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            Claim claim = repository.claimOne().orElseThrow();
            assertThat(integer("attempt_count")).isEqualTo(attempt);
            repository.markFailed(claim, false, "CONFIRM_TIMEOUT");
            if (attempt < 3) {
                makeRetryDue();
            }
        }
        assertThat(text("publish_status")).isEqualTo("BLOCKED");
        assertThat(time("next_attempt_at")).isNull();
        assertThat(repository.claimOne()).isEmpty();
    }

    @Test
    @DisplayName("최초 실패 후 30분이 지난 작업은 추가 발행 없이 차단한다")
    void exhaustedBudget_blocksWithoutAnotherAttempt() {
        Claim claim = repository.claimOne().orElseThrow();
        repository.markFailed(claim, false, "DISCONNECTED");
        jdbc.update("""
                UPDATE profiling_jobs SET first_failed_at = UTC_TIMESTAMP(6) - INTERVAL 31 MINUTE,
                next_attempt_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE request_id = ?
                """, requestId);
        assertThat(repository.claimOne()).isEmpty();
        assertThat(text("publish_status")).isEqualTo("BLOCKED");
        assertThat(integer("attempt_count")).isEqualTo(1);
    }

    @Test
    @DisplayName("만료 선점을 실패로 회수하며 늦은 성공을 거부하고 시도 횟수를 중복 증가시키지 않는다")
    void expiredClaim_recoversAndRejectsLateConfirmation() {
        Claim expired = repository.claimOne().orElseThrow();
        jdbc.update("""
                UPDATE profiling_jobs SET claimed_at = UTC_TIMESTAMP(6) - INTERVAL 121 SECOND
                WHERE request_id = ?
                """, requestId);
        assertThat(repository.markPublished(expired)).isFalse();
        assertThat(repository.markFailed(expired, false, "LATE_FAILURE")).isFalse();
        assertThat(repository.releaseUnsent(expired)).isFalse();
        assertThat(repository.claimOne()).isEmpty();
        assertThat(text("publish_status")).isEqualTo("RETRY_WAIT");
        assertThat(text("last_error_code")).isEqualTo("CLAIM_EXPIRED");
        assertThat(integer("attempt_count")).isEqualTo(1);
        makeRetryDue();
        assertThat(repository.claimOne().orElseThrow().token()).isNotEqualTo(expired.token());
        assertThat(integer("attempt_count")).isEqualTo(2);
    }

    @Test
    @DisplayName("전송 전 선점 반납은 이전 재시도 시각과 시도 횟수를 복원한다")
    void releaseUnsent_restoresRetryState() {
        Claim first = repository.claimOne().orElseThrow();
        repository.markFailed(first, false, "DISCONNECTED");
        makeRetryDue();
        LocalDateTime retryAt = time("next_attempt_at");
        Claim second = repository.claimOne().orElseThrow();
        assertThat(repository.releaseUnsent(second)).isTrue();
        assertThat(text("publish_status")).isEqualTo("RETRY_WAIT");
        assertThat(integer("attempt_count")).isEqualTo(1);
        assertThat(time("next_attempt_at")).isEqualTo(retryAt);
        assertThat(text("claim_token")).isNull();
    }

    @Test
    @DisplayName("영구 실패는 첫 시도여도 차단한다")
    void permanentFailure_blocksImmediately() {
        repository.markFailed(repository.claimOne().orElseThrow(), true, "UNROUTABLE_MESSAGE");
        assertThat(text("publish_status")).isEqualTo("BLOCKED");
        assertThat(repository.claimOne()).isEmpty();
    }

    @Test
    @DisplayName("이미 결과가 확정된 작업은 발행하거나 늦은 발행 결과로 변경하지 않는다")
    void terminalResult_preventsPublishingUpdates() {
        Claim claim = repository.claimOne().orElseThrow();
        jdbc.update("UPDATE profiling_jobs SET result_status = 'COMPLETED' WHERE request_id = ?", requestId);
        assertThat(repository.markPublished(claim)).isFalse();
        assertThat(repository.markFailed(claim, true, "LATE_FAILURE")).isFalse();
        assertThat(repository.releaseUnsent(claim)).isFalse();
        assertThat(repository.claimOne()).isEmpty();
    }

    @Test
    @DisplayName("다른 트랜잭션이 잠근 작업은 SKIP LOCKED로 건너뛰고 다음 작업을 선점한다")
    void claim_skipsLockedJob() throws Exception {
        String secondId = insertJob(2);
        jdbc.update("UPDATE profiling_jobs SET created_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE request_id = ?",
                requestId);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT request_id FROM profiling_jobs WHERE request_id = ? FOR UPDATE",
                        String.class, requestId);
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("잠금 해제 대기 시간 초과");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            try {
                assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(repository.claimOne().orElseThrow().requestId()).isEqualTo(secondId);
            } finally {
                release.countDown();
            }
            holder.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void backlog_excludesPublishedAndTerminalJobs() {
        String blocked = insertJob(2);
        String published = insertJob(3);
        String terminal = insertJob(4);
        jdbc.update("UPDATE profiling_jobs SET publish_status = 'BLOCKED' WHERE request_id = ?", blocked);
        jdbc.update("UPDATE profiling_jobs SET publish_status = 'PUBLISHED' WHERE request_id = ?", published);
        jdbc.update("UPDATE profiling_jobs SET result_status = 'COMPLETED' WHERE request_id = ?", terminal);
        assertThat(repository.findPublishBacklog()).extracting(ProfilingPublishStore.PublishBacklog::publishStatus)
                .containsExactlyInAnyOrder("READY", "BLOCKED");
        assertThat(repository.findPublishBacklog()).allSatisfy(row -> assertThat(row.count()).isEqualTo(1));
    }

    private String insertJob(long version) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO profiling_jobs
                (request_id, profile_id, source_version, request_body, created_at, updated_at)
                VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, id, profileId, version, BODY);
        return id;
    }

    private void makeRetryDue() {
        jdbc.update("""
                UPDATE profiling_jobs SET next_attempt_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND
                WHERE request_id = ?
                """, requestId);
    }

    private String text(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM profiling_jobs WHERE request_id = ?",
                String.class, requestId);
    }

    private Integer integer(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM profiling_jobs WHERE request_id = ?",
                Integer.class, requestId);
    }

    private LocalDateTime time(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM profiling_jobs WHERE request_id = ?",
                LocalDateTime.class, requestId);
    }
}
