package com.gift.gift.domain.notification.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.gift.gift.domain.gift.event.GiftCreatedEvent;
import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;
import com.gift.gift.domain.notification.entity.Notification;
import com.gift.gift.domain.notification.event.handler.GiftNotificationEventHandler;
import com.gift.gift.domain.notification.repository.NotificationRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class NotificationCreateConcurrencyIntegrationTest {

    private static final int REQUEST_COUNT = 3;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private GiftNotificationEventHandler giftNotificationEventHandler;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdRecipientIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Long recipientId : createdRecipientIds) {
            jdbcTemplate.update("DELETE FROM notifications WHERE recipient_id = ?", recipientId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", recipientId);
        }
        createdRecipientIds.clear();
    }

    @Test
    @DisplayName("동일 deduplication key 동시 생성은 Notification 1건만 저장한다")
    void create_concurrentlyStoresOnlyOneNotification() throws Exception {
        Long recipientId = userRepository.saveAndFlush(newUser()).getId();
        createdRecipientIds.add(recipientId);
        String deduplicationKey = "GIFT_RECEIVED:concurrent-" + UUID.randomUUID() + ":" + recipientId;
        NotificationCreateCommand command = new NotificationCreateCommand(
                recipientId,
                "501",
                NotificationType.GIFT_RECEIVED,
                NotificationReferenceType.GIFT,
                501L,
                "선물이 도착했어요",
                "새 선물이 도착했습니다.",
                deduplicationKey
        );

        ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < REQUEST_COUNT; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    notificationService.create(command);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        List<Notification> saved = notificationRepository.findAll().stream()
                .filter(notification -> deduplicationKey.equals(notification.getDeduplicationKey()))
                .toList();

        assertThat(saved).hasSize(1);
    }

    @Test
    @DisplayName("같은 Gift Event를 두 번 처리해도 Notification 1건만 저장한다")
    void handleSameGiftEventTwiceStoresOnlyOneNotification() {
        Long recipientId = userRepository.saveAndFlush(newUser()).getId();
        createdRecipientIds.add(recipientId);
        Long giftId = 502L;
        GiftCreatedEvent event = new GiftCreatedEvent(giftId, recipientId, "선물 상품");

        giftNotificationEventHandler.handle(event);
        giftNotificationEventHandler.handle(event);

        String deduplicationKey = "GIFT_RECEIVED:" + giftId + ":" + recipientId;
        List<Notification> saved = notificationRepository.findAll().stream()
                .filter(notification -> deduplicationKey.equals(notification.getDeduplicationKey()))
                .toList();

        assertThat(saved).hasSize(1);
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString();
        return new User(
                "notification-concurrency-" + suffix + "@example.com",
                "$2a$10$" + "a".repeat(53),
                "동시성 사용자",
                LocalDate.of(1990, 1, 1)
        );
    }
}
