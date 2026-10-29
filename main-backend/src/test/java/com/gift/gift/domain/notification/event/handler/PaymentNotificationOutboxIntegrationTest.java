package com.gift.gift.domain.notification.event.handler;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.gift.event.GiftCompletedRewardEvent;
import com.gift.gift.domain.notification.entity.NotificationType;
import com.gift.gift.domain.notification.service.NotificationOutboxDispatcher;
import com.gift.gift.domain.order.event.PaymentCanceledEvent;
import com.gift.gift.domain.order.event.PaymentCompletedEvent;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentNotificationOutboxIntegrationTest {

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private NotificationOutboxDispatcher dispatcher;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private Long recipientId;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        recipientId = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                "$2a$10$" + "a".repeat(53), "변경 전", LocalDate.of(1990, 1, 1))).getId();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_id = ?", recipientId);
        jdbc.update("DELETE FROM notifications WHERE recipient_id = ?", recipientId);
        jdbc.update("DELETE FROM users WHERE id = ?", recipientId);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationType.class,
            names = {"PAYMENT_COMPLETED", "PAYMENT_CANCELED", "GIFT_COMPLETED_REWARD"})
    @DisplayName("결제·보상 이벤트는 커밋된 작업으로 남고 중복 발행·처리에도 알림 한 건만 생성한다")
    void committedEventIsDurableAndIdempotent(NotificationType type) {
        Object event = event(type, recipientId);
        transaction.executeWithoutResult(tx -> {
            publisher.publishEvent(event);
            publisher.publishEvent(event);
            assertThat(outboxCount()).isZero();
        });
        transaction.executeWithoutResult(tx -> publisher.publishEvent(event));

        assertThat(outboxCount()).isEqualTo(1);
        assertThat(notificationCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE recipient_id = ?",
                String.class, recipientId)).isEqualTo("PENDING");

        dispatcher.dispatch();
        dispatcher.dispatch();

        assertThat(notificationCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT type FROM notifications WHERE recipient_id = ?",
                String.class, recipientId)).isEqualTo(type.name());
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE recipient_id = ?",
                String.class, recipientId)).isEqualTo("PROCESSED");
    }

    @ParameterizedTest
    @EnumSource(value = NotificationType.class,
            names = {"PAYMENT_COMPLETED", "PAYMENT_CANCELED", "GIFT_COMPLETED_REWARD"})
    @DisplayName("결제·보상 이벤트를 발행한 트랜잭션이 롤백되면 알림 작업도 남지 않는다")
    void rollbackDoesNotLeaveNotificationIntent(NotificationType type) {
        transaction.executeWithoutResult(tx -> {
            publisher.publishEvent(event(type, recipientId));
            tx.setRollbackOnly();
        });
        dispatcher.dispatch();
        assertThat(outboxCount()).isZero();
        assertThat(notificationCount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationType.class,
            names = {"PAYMENT_COMPLETED", "PAYMENT_CANCELED", "GIFT_COMPLETED_REWARD"})
    @DisplayName("Outbox FK 저장 실패는 이벤트 발행 트랜잭션의 DB 변경도 롤백한다")
    void outboxFailureRollsBackPublishingTransaction(NotificationType type) {
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
            // 동일 트랜잭션의 DB 변경으로 커밋 이전 실패의 원자성을 확인한다.
            jdbc.update("UPDATE users SET name = '변경 후' WHERE id = ?", recipientId);
            publisher.publishEvent(event(type, recipientId));
            publisher.publishEvent(event(type, Long.MAX_VALUE));
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, recipientId))
                .isEqualTo("변경 전");
        assertThat(outboxCount()).isZero();
        assertThat(notificationCount()).isZero();
    }

    private Object event(NotificationType type, Long recipient) {
        return switch (type) {
            case PAYMENT_COMPLETED -> new PaymentCompletedEvent(101L, recipient, 501L, "상품");
            case PAYMENT_CANCELED -> new PaymentCanceledEvent(101L, recipient, 501L, "상품");
            case GIFT_COMPLETED_REWARD -> new GiftCompletedRewardEvent(501L, recipient, "상품", 100_000L, 2);
            default -> throw new IllegalArgumentException("테스트 대상 알림이 아닙니다.");
        };
    }

    private int outboxCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id = ?",
                Integer.class, recipientId);
    }

    private int notificationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id = ?",
                Integer.class, recipientId);
    }
}
