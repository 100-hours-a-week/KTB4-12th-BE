package com.gift.gift.domain.notification.service;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.gift.gift.domain.notification.repository.NotificationReplayRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class NotificationReplayIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationReplayService service;
    @Autowired private NotificationReplayRepository repository;
    @Autowired private UserRepository users;
    private Long recipient;
    private final String key = "replay:" + UUID.randomUUID();

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM outbox_event WHERE deduplication_key=?", key);
        jdbc.update("DELETE FROM notification_outbox WHERE deduplication_key=?", key);
        if (recipient != null) jdbc.update("DELETE FROM users WHERE id=?", recipient);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("공통·기존 FAILED만 dry-run 무변경·낙관적 경합 보호·재시도 초기화하고 완료 원본은 유지한다")
    void requeuesOnlyUnchangedFailedNotification(boolean legacy) {
        recipient = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "$2a$10$" + "a".repeat(53),
            "재처리", LocalDate.of(1990, 1, 1))).getId();
        String table = legacy ? "notification_outbox" : "outbox_event";
        if (legacy) {
            jdbc.update("""
                INSERT INTO notification_outbox(recipient_id,source_identifier,type,title,message,deduplication_key,
                    status,failure_count,next_attempt_at,last_error_type,created_at,updated_at)
                VALUES (?,'fixture','FRIEND_REQUEST_RECEIVED','원본','원본',?,'FAILED',9,
                    TIMESTAMPADD(DAY,1,NOW(6)),'InvalidData',NOW(6),NOW(6))
                """, recipient, key);
        } else {
            jdbc.update("""
                INSERT INTO outbox_event(event_id,event_type,schema_version,deduplication_key,aggregate_type,
                    aggregate_id,payload,occurred_at,status,failure_count,next_attempt_at,last_error_type)
                VALUES (?,'notification.create.v1',1,?,'USER',?,JSON_OBJECT('original','preserved'),NOW(6),
                    'FAILED',9,TIMESTAMPADD(DAY,1,NOW(6)),'InvalidData')
                """, UUID.randomUUID().toString(), key, recipient.toString());
        }
        Long id = jdbc.queryForObject("SELECT id FROM " + table + " WHERE deduplication_key=?", Long.class, key);
        var original = jdbc.queryForMap("SELECT * FROM " + table + " WHERE id=?", id);
        assertThat(service.replay(id, legacy, false)).isEqualTo("DRY_RUN_ELIGIBLE");
        assertThat(jdbc.queryForMap("SELECT * FROM " + table + " WHERE id=?", id)).isEqualTo(original);

        var snapshot = repository.findFailed(id, legacy).orElseThrow();
        jdbc.update("UPDATE " + table + " SET failure_count=10 WHERE id=?", id);
        assertThat(repository.requeueIfUnchanged(snapshot)).isFalse();
        assertThat(service.replay(id, legacy, true)).isEqualTo("REQUEUED");
        var changed = jdbc.queryForMap("SELECT * FROM " + table + " WHERE id=?", id);
        assertThat(changed.get("status")).isEqualTo("PENDING");
        assertThat(((Number) changed.get("failure_count")).intValue()).isZero();
        assertThat(changed.get("last_error_type")).isNull();
        assertThat(jdbc.queryForObject("SELECT next_attempt_at<=NOW(6) FROM " + table + " WHERE id=?",
            Boolean.class, id)).isTrue();
        for (String field : legacy ? new String[]{"recipient_id", "title", "message", "deduplication_key"}
                : new String[]{"event_id", "event_type", "payload", "deduplication_key", "occurred_at"}) {
            assertThat(changed.get(field)).isEqualTo(original.get(field));
        }
        assertThat(service.replay(id, legacy, true)).isEqualTo("NOT_FAILED_OR_MISSING");
        jdbc.update("UPDATE " + table + " SET status='PROCESSED',processed_at=NOW(6) WHERE id=?", id);
        var processed = jdbc.queryForMap("SELECT * FROM " + table + " WHERE id=?", id);
        assertThat(service.replay(id, legacy, true)).isEqualTo("NOT_FAILED_OR_MISSING");
        assertThat(jdbc.queryForMap("SELECT * FROM " + table + " WHERE id=?", id)).isEqualTo(processed);
        if (!legacy) {
            jdbc.update("UPDATE outbox_event SET status='FAILED',processed_at=NULL,event_type='report.created.v1'"
                + " WHERE id=?", id);
            assertThat(service.replay(id, false, true)).isEqualTo("NOT_FAILED_OR_MISSING");
        }
    }
}
