package com.gift.gift.domain.notification.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.notification.entity.Notification;
import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;
import com.gift.gift.domain.notification.support.NotificationCursor;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional
class NotificationQueryRepositoryTest {

    @Autowired
    private NotificationQueryRepository notificationQueryRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("수신자별 미읽음 개수와 삭제되지 않은 알림 목록을 조회한다")
    void findNotifications_filtersByRecipientAndDeletedState() {
        User recipient = persistUser();
        Notification unread = notification(recipient.getId(), 1L, false);
        Notification read = notification(recipient.getId(), 2L, true);
        Notification deleted = notification(recipient.getId(), 3L, false);
        Notification anotherRecipient = notification(persistUser().getId(), 4L, false);

        entityManager.persist(unread);
        entityManager.persist(read);
        entityManager.persist(deleted);
        entityManager.persist(anotherRecipient);
        entityManager.flush();
        deleted.markAsRead(java.time.LocalDateTime.now());
        entityManager.createQuery("UPDATE Notification n SET n.deletedAt = CURRENT_TIMESTAMP WHERE n.id = :id")
                .setParameter("id", deleted.getId())
                .executeUpdate();
        entityManager.clear();

        List<NotificationQueryRow> rows = notificationQueryRepository.findNotifications(recipient.getId(), null);

        assertThat(rows).extracting(NotificationQueryRow::notificationId)
                .containsExactly(read.getId(), unread.getId());
        assertThat(notificationQueryRepository.countUnreadNotifications(recipient.getId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("같은 생성 시각에서는 ID를 기준으로 커서 이후 알림만 조회한다")
    void findNotifications_appliesCreatedAtAndIdCursor() {
        User recipient = persistUser();
        Notification first = notification(recipient.getId(), 10L, false);
        Notification second = notification(recipient.getId(), 11L, false);
        entityManager.persist(first);
        entityManager.persist(second);
        entityManager.flush();
        entityManager.clear();

        NotificationQueryRow newest = notificationQueryRepository
                .findNotifications(recipient.getId(), null)
                .getFirst();
        NotificationCursor cursor = new NotificationCursor(newest.notifiedAt(), newest.notificationId());

        List<NotificationQueryRow> olderRows = notificationQueryRepository
                .findNotifications(recipient.getId(), cursor);

        assertThat(olderRows).hasSize(1);
        assertThat(olderRows.getFirst().notificationId()).isNotEqualTo(newest.notificationId());
    }

    private User persistUser() {
        String suffix = UUID.randomUUID().toString();
        User user = new User(
                "notification-" + suffix + "@example.com",
                "$2a$10$" + "a".repeat(53),
                "알림 사용자",
                LocalDate.of(1990, 1, 1)
        );
        entityManager.persist(user);
        entityManager.flush();
        return user;
    }

    private Notification notification(Long recipientId, Long sourceId, boolean read) {
        Notification notification = new Notification(
                recipientId,
                NotificationType.GIFT_RECEIVED,
                NotificationReferenceType.GIFT,
                sourceId,
                "선물이 도착했어요",
                "새 선물이 도착했습니다.",
                "GIFT_RECEIVED:" + sourceId + ":" + recipientId + ":" + UUID.randomUUID()
        );
        if (read) {
            notification.markAsRead(java.time.LocalDateTime.now());
        }
        return notification;
    }
}
