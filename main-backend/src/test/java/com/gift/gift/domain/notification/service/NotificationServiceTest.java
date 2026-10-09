package com.gift.gift.domain.notification.service;

import java.util.Optional;
import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.gift.gift.domain.notification.entity.Notification;
import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;
import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.query.NotificationPageAssembler;
import com.gift.gift.domain.notification.repository.NotificationQueryRepository;
import com.gift.gift.domain.notification.repository.NotificationRepository;
import com.gift.gift.domain.notification.dto.response.NotificationReadResponse;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    private NotificationRepository notificationRepository;
    private NotificationQueryRepository notificationQueryRepository;
    private NotificationCreateTransactionService notificationCreateTransactionService;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        notificationQueryRepository = mock(NotificationQueryRepository.class);
        notificationCreateTransactionService = mock(NotificationCreateTransactionService.class);
        notificationService = new NotificationService(
                notificationQueryRepository,
                notificationRepository,
                notificationCreateTransactionService,
                mock(NotificationPageAssembler.class),
                mock(OpaqueCursorCodec.class)
        );
    }

    @Test
    @DisplayName("deduplication UNIQUE 충돌은 정상 중복으로 처리한다")
    void create_ignoresDeduplicationConstraintViolation() {
        NotificationCreateCommand command = command();
        when(notificationRepository.existsByDeduplicationKey(command.deduplicationKey())).thenReturn(false);
        org.mockito.Mockito.doThrow(deduplicationViolation())
                .when(notificationCreateTransactionService).save(command);

        assertThatCode(() -> notificationService.create(command)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("deduplication 외 무결성 오류는 그대로 전파한다")
    void create_propagatesOtherConstraintViolation() {
        NotificationCreateCommand command = command();
        DataIntegrityViolationException violation = otherConstraintViolation();
        when(notificationRepository.existsByDeduplicationKey(command.deduplicationKey())).thenReturn(false);
        org.mockito.Mockito.doThrow(violation)
                .when(notificationCreateTransactionService).save(command);

        assertThatThrownBy(() -> notificationService.create(command)).isSameAs(violation);
    }

    @Test
    @DisplayName("본인 알림을 읽음 처리하고 남은 미읽음 개수를 반환한다")
    void markAsRead_returnsUnreadCountForOwner() {
        Notification notification = notification();
        when(notificationRepository.findByIdAndRecipientIdAndDeletedAtIsNull(10L, 20L))
                .thenReturn(Optional.of(notification));
        when(notificationQueryRepository.countUnreadNotifications(20L)).thenReturn(3L);

        NotificationReadResponse response = notificationService.markAsRead(20L, 10L);

        assertThat(response.isRead()).isTrue();
        assertThat(response.unreadCount()).isEqualTo(3L);
        verify(notificationQueryRepository).countUnreadNotifications(20L);
    }

    @Test
    @DisplayName("본인 소유가 아니거나 삭제된 알림은 404 예외를 발생시킨다")
    void markAsRead_throwsNotFound_whenNotificationIsNotAccessible() {
        when(notificationRepository.findByIdAndRecipientIdAndDeletedAtIsNull(10L, 20L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markAsRead(20L, 10L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
                });
    }

    private Notification notification() {
        Notification notification = new Notification(
                20L,
                NotificationType.GIFT_RECEIVED,
                NotificationReferenceType.GIFT,
                501L,
                "선물이 도착했어요",
                "새 선물이 도착했습니다.",
                "GIFT_RECEIVED:501:20"
        );

        return notification;
    }

    private NotificationCreateCommand command() {
        return new NotificationCreateCommand(
                20L,
                "501",
                NotificationType.GIFT_RECEIVED,
                NotificationReferenceType.GIFT,
                501L,
                "선물이 도착했어요",
                "새 선물이 도착했습니다.",
                "GIFT_RECEIVED:501:20"
        );
    }

    private DataIntegrityViolationException deduplicationViolation() {
        ConstraintViolationException cause = new ConstraintViolationException(
                "중복 키 충돌",
                new SQLException("Duplicate entry", "23000", 1062),
                "notifications.uk_notifications_deduplication_key"
        );
        return new DataIntegrityViolationException("중복 키 충돌", cause);
    }

    private DataIntegrityViolationException otherConstraintViolation() {
        ConstraintViolationException cause = new ConstraintViolationException(
                "다른 제약 위반",
                new SQLException("Constraint violation", "23000", 4025),
                "chk_notifications_reference_pair"
        );
        return new DataIntegrityViolationException("다른 제약 위반", cause);
    }
}
