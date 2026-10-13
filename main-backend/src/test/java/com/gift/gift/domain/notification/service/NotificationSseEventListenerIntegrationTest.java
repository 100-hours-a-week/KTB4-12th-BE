package com.gift.gift.domain.notification.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.dto.response.NotificationListItem;
import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;
import com.gift.gift.domain.notification.repository.NotificationRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
class NotificationSseEventListenerIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @MockitoBean
    private NotificationSseHub sseHub;

    private Long recipientId;

    @AfterEach
    void tearDown() {
        if (recipientId != null) {
            jdbcTemplate.update("DELETE FROM notifications WHERE recipient_id = ?", recipientId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", recipientId);
        }
    }

    @Test
    @DisplayName("알림 저장 트랜잭션 커밋 후 SSE 이벤트를 전달한다")
    void create_publishesNotificationAfterCommit() {
        recipientId = userRepository.saveAndFlush(newUser()).getId();
        String deduplicationKey = "SSE_TEST:" + UUID.randomUUID();

        notificationService.create(new NotificationCreateCommand(
                recipientId,
                "sse-test",
                NotificationType.GIFT_RECEIVED,
                NotificationReferenceType.GIFT,
                501L,
                "선물이 도착했어요",
                "새 선물이 도착했습니다.",
                deduplicationKey
        ));

        var notification = notificationRepository.findAll().stream()
                .filter(item -> deduplicationKey.equals(item.getDeduplicationKey()))
                .findFirst()
                .orElseThrow();
        verify(sseHub).publish(recipientId, NotificationListItem.from(notification));
    }

    @Test
    @DisplayName("알림 이벤트가 롤백되면 SSE 이벤트를 전달하지 않는다")
    void event_doesNotPublishWhenTransactionRollsBack() {
        NotificationCreatedEvent event = new NotificationCreatedEvent(
                123L,
                new NotificationListItem(
                        1L,
                        NotificationType.GIFT_RECEIVED,
                        "선물이 도착했어요",
                        "새 선물이 도착했습니다.",
                        NotificationReferenceType.GIFT,
                        501L,
                        LocalDateTime.now(),
                        false
                )
        );

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publishEvent(event);
            status.setRollbackOnly();
        });

        verifyNoInteractions(sseHub);
    }

    private User newUser() {
        return new User(
                "notification-sse-" + UUID.randomUUID() + "@example.com",
                "$2a$10$" + "a".repeat(53),
                "SSE 사용자",
                LocalDate.of(1990, 1, 1)
        );
    }
}
