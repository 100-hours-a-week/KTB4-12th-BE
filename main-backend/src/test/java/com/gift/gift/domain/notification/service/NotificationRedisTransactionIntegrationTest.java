package com.gift.gift.domain.notification.service;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class NotificationRedisTransactionIntegrationTest {
    @Autowired private NotificationCreateTransactionService creation;
    @Autowired private NotificationOutboxWriter writer;
    @Autowired private NotificationOutboxTransactionService processor;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private StringRedisTemplate redis;
    private Long recipient;

    @BeforeEach
    void setup() {
        recipient = users.saveAndFlush(new User("sse-" + UUID.randomUUID() + "@example.com",
                "$2a$10$" + "a".repeat(53), "SSE 테스트", LocalDate.of(1990, 1, 1))).getId();
    }
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_id = ?", recipient);
        jdbc.update("DELETE FROM notifications WHERE recipient_id = ?", recipient);
        jdbc.update("DELETE FROM users WHERE id = ?", recipient);
    }
    @Test
    @DisplayName("커밋 전에는 발행하지 않고 롤백된 알림은 Redis에 발행하지 않는다")
    void rollback_neverPublishes() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            creation.saveInCurrentTransaction(command());
            verifyNoInteractions(redis);
            status.setRollbackOnly();
        });
        verifyNoInteractions(redis);
        assertThat(notificationCount()).isZero();
    }
    @Test
    @DisplayName("커밋된 알림 ID와 본문을 사용자 채널에 한 번 발행한다")
    void commit_publishesPersistedNotification() {
        var command = command();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            creation.saveInCurrentTransaction(command);
            verifyNoInteractions(redis);
        });
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq(NotificationRedisPublisher.channel(recipient)), payload.capture());
        var event = mapper.readValue(payload.getValue(), NotificationCreatedEvent.class);
        assertThat(event.recipientId()).isEqualTo(recipient);
        assertThat(event.notification().notificationId()).isEqualTo(jdbc.queryForObject(
                "SELECT id FROM notifications WHERE recipient_id = ?", Long.class, recipient));
        assertThat(event.notification().title()).isEqualTo(command.title());
        assertThat(event.notification().notifiedAt()).isNotNull();
    }
    @Test
    @DisplayName("Redis 발행 실패에도 알림과 Outbox 완료는 보존하고 생성 재시도를 하지 않는다")
    void publishFailure_preservesNotificationAndProcessedOutbox() {
        when(redis.convertAndSend(anyString(), anyString())).thenThrow(new RedisConnectionFailureException("test outage"));
        var command = command();
        new TransactionTemplate(manager).executeWithoutResult(status -> writer.enqueue(command));
        Long id = jdbc.queryForObject("SELECT id FROM notification_outbox WHERE recipient_id = ?", Long.class, recipient);
        assertThatCode(() -> processor.process(id)).doesNotThrowAnyException();
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE id = ?", String.class, id))
                .isEqualTo("PROCESSED");
        processor.process(id);
        verify(redis, times(1)).convertAndSend(anyString(), anyString());
    }
    @Test
    @DisplayName("마이그레이션은 중계 테이블만 제거하고 원본·읽음·Outbox 스키마를 유지한다")
    void migration_removesOnlyRelayTables() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                + " AND table_name IN ('notification_sse_connections', 'notification_sse_deliveries')", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                + " AND table_name IN ('notifications', 'notification_outbox')", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()"
                + " AND table_name = 'notifications' AND column_name = 'read_at'", Long.class)).isEqualTo(1);
    }
    private long notificationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id = ?", Long.class, recipient);
    }
    private NotificationCreateCommand command() {
        return new NotificationCreateCommand(recipient, "sse-test", NotificationType.FRIEND_REQUEST_RECEIVED,
                NotificationReferenceType.FRIEND_REQUEST, 501L, "친구 요청", "친구 요청이 도착했습니다.",
                "SSE_TEST:" + UUID.randomUUID());
    }
}
