package com.gift.gift.global.outbox;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.global.outbox.OutboxClaimRepository.Job;
import com.gift.gift.global.outbox.OutboxEventWriter.Event;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "app.scheduling.enabled=false")
class OutboxProcessingContractIntegrationTest {
    @Autowired private OutboxEventWriter writer;
    @Autowired private OutboxClaimRepository claims;
    @Autowired private OutboxRetentionService retention;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<String> eventIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        eventIds.forEach(id -> jdbc.update("DELETE FROM outbox_event WHERE event_id = ?", id));
    }

    private String enqueue(String type) {
        String id = new TransactionTemplate(transactionManager).execute(status -> writer.enqueue(
                new Event(type, 1, UUID.randomUUID().toString(), "TEST", "42", null,
                        mapper.readTree("{\"value\":1}"), LocalDateTime.now(), null)));
        eventIds.add(id);
        return id;
    }

    @Test
    @DisplayName("알림은 MQ 선점과 만료 회수에서 제외하고 기존 검색 선점은 유지한다")
    void excludesNotificationsWithoutChangingSearchClaims() {
        String notification = enqueue("notification.create.v1");
        String search = enqueue("product.search.sync.v1");
        assertThatThrownBy(() -> claims.claim("notification.create.v1", 10))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThat(state(notification)).isEqualTo("PENDING");
        assertThat(claims.claim("product.search.sync.v1", 1000))
                .anySatisfy(job -> assertThat(job.envelope().eventId()).isEqualTo(search));

        jdbc.update("""
                UPDATE outbox_event SET status='PUBLISHING', claim_token='old-token',
                    lease_until=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE event_id=?
                """, notification);
        assertThatThrownBy(() -> claims.claim("notification.create.v1", 10))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThat(state(notification)).isEqualTo("PUBLISHING");
        assertThat(jdbc.queryForObject("SELECT failure_count FROM outbox_event WHERE event_id=?",
                Integer.class, notification)).isZero();
    }

    @Test
    @DisplayName("기존 알림 MQ 선점 결과가 늦게 도착해도 발행 완료나 실패로 변경하지 않는다")
    void fencesLegacyNotificationPublishResults() {
        String notification = enqueue("notification.create.v1");
        jdbc.update("""
                UPDATE outbox_event SET status='PUBLISHING', claim_token='old-token',
                    lease_until=TIMESTAMPADD(SECOND,60,NOW(6)) WHERE event_id=?
                """, notification);
        long id = jdbc.queryForObject("SELECT id FROM outbox_event WHERE event_id=?", Long.class, notification);
        Job old = new Job(id, "old-token", null);
        assertThat(claims.complete(old)).isFalse();
        claims.fail(old, "LateFailure");
        assertThat(state(notification)).isEqualTo("PUBLISHING");
    }

    @Test
    @DisplayName("처리 완료는 처리 시각만 갖고 선점 정보와 발행 완료 시각을 가질 수 없다")
    void rejectsInvalidCompletionAndClaimCombinations() {
        String id = enqueue("notification.create.v1");
        for (String invalidUpdate : List.of(
                "status='PROCESSED'",
                "processed_at=NOW(6)",
                "status='PROCESSED',processed_at=NOW(6),published_at=NOW(6)",
                "status='PROCESSED',processed_at=NOW(6),claim_token='token',lease_until=NOW(6)",
                "status='PUBLISHED',published_at=NOW(6),processed_at=NOW(6)",
                "status='FAILED',processed_at=NOW(6)",
                "status='PUBLISHING',claim_token='token',lease_until=NOW(6),processed_at=NOW(6)")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE outbox_event SET " + invalidUpdate + " WHERE event_id=?", id))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().isInstanceOfSatisfying(SQLException.class, failure ->
                            assertThat(failure.getErrorCode()).isEqualTo(3819));
        }
        jdbc.update("UPDATE outbox_event SET status='PROCESSED',processed_at=NOW(6) WHERE event_id=?", id);
        assertThat(state(id)).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("SELECT published_at FROM outbox_event WHERE event_id=?",
                LocalDateTime.class, id)).isNull();
    }

    @Test
    @DisplayName("DB 처리 완료 상태는 호출 트랜잭션이 롤백되면 함께 롤백할 수 있다")
    void processedStateParticipatesInBusinessTransaction() {
        String id = enqueue("notification.create.v1");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM outbox_event WHERE event_id=? FOR UPDATE", Long.class, id);
            jdbc.update("UPDATE outbox_event SET status='PROCESSED',processed_at=NOW(6) WHERE event_id=?", id);
            status.setRollbackOnly();
        });
        assertThat(state(id)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("7일 정리는 생성 시각 대신 완료 시각을 사용하고 미완료 및 다른 도메인을 보존한다")
    void deletesOnlyExpiredReportAndNotificationCompletions() {
        String oldReport = completed("report.created.v1", "PUBLISHED", 8);
        String oldNotification = completed("notification.create.v1", "PROCESSED", 8);
        String recentReport = completed("report.created.v1", "PUBLISHED", 1);
        String recentNotification = completed("notification.create.v1", "PROCESSED", 1);
        String search = completed("product.search.sync.v1", "PUBLISHED", 8);
        String legacyNotification = completed("notification.create.v1", "PUBLISHED", 8);
        String pending = enqueue("report.created.v1");
        String failed = enqueue("notification.create.v1");
        String publishing = enqueue("report.created.v1");
        jdbc.update("UPDATE outbox_event SET status='FAILED' WHERE event_id=?", failed);
        jdbc.update("""
                UPDATE outbox_event SET status='PUBLISHING',claim_token='token',
                    lease_until=TIMESTAMPADD(DAY,-8,NOW(6)) WHERE event_id=?
                """, publishing);
        for (String id : List.of(pending, failed, publishing)) {
            jdbc.update("UPDATE outbox_event SET created_at=TIMESTAMPADD(DAY,-30,NOW(6)) WHERE event_id=?", id);
        }
        retention.deleteCompletedEvents();
        for (String id : List.of(oldReport, oldNotification)) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_id=?", Integer.class, id))
                    .isZero();
        }
        for (String id : List.of(recentReport, recentNotification, search, legacyNotification, pending, failed, publishing)) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_id=?", Integer.class, id))
                    .as("보존할 이벤트 %s", id).isEqualTo(1);
        }
    }

    private String completed(String type, String state, int ageDays) {
        String id = enqueue(type);
        String column = state.equals("PROCESSED") ? "processed_at" : "published_at";
        jdbc.update("UPDATE outbox_event SET status=?, " + column
                        + "=TIMESTAMPADD(DAY,?,NOW(6)),created_at=TIMESTAMPADD(DAY,-30,NOW(6)) WHERE event_id=?",
                state, -ageDays, id);
        return id;
    }

    private String state(String id) {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, id);
    }
}
