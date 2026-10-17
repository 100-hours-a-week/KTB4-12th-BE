package com.gift.gift.domain.notification.service;

import java.util.Optional;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.CannotCreateTransactionException;

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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;

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
        verify(notificationCreateTransactionService, times(1)).save(command);
    }

    @Test
    @DisplayName("일시적인 잠금 오류 이후 새 저장 시도로 복구한다")
    void create_retriesTransientSaveFailure() {
        NotificationCreateCommand command = command();
        doThrow(new CannotAcquireLockException("lock timeout")).doNothing()
                .when(notificationCreateTransactionService).save(command);

        notificationService.create(command);

        verify(notificationCreateTransactionService, times(2)).save(command);
    }

    @Test
    @DisplayName("DB 연결 실패는 총 세 번 시도하고 마지막 오류를 전파한다")
    void create_stopsAfterThreeResourceFailures() {
        NotificationCreateCommand command = command();
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("DB unavailable");
        doThrow(failure).when(notificationCreateTransactionService).save(command);

        assertThatThrownBy(() -> notificationService.create(command)).isSameAs(failure);

        verify(notificationCreateTransactionService, times(3)).save(command);
    }

    @Test
    @DisplayName("트랜잭션 시작 중 일시적인 커넥션 풀 고갈은 재시도한다")
    void create_retriesTransactionConnectionTimeout() {
        NotificationCreateCommand command = command();
        doThrow(new CannotCreateTransactionException("cannot begin", new SQLTransientConnectionException("pool busy")))
                .doNothing().when(notificationCreateTransactionService).save(command);

        notificationService.create(command);

        verify(notificationCreateTransactionService, times(2)).save(command);
    }

    @Test
    @DisplayName("드라이버의 연결 끊김 SQLState 08도 새 트랜잭션으로 재시도한다")
    void create_retriesTransactionConnectionState() {
        NotificationCreateCommand command = command();
        doThrow(new CannotCreateTransactionException("cannot begin",
                new SQLNonTransientConnectionException("connection lost", "08006")))
                .doNothing().when(notificationCreateTransactionService).save(command);

        notificationService.create(command);

        verify(notificationCreateTransactionService, times(2)).save(command);
    }

    @Test
    @DisplayName("트랜잭션 시작 중 DB 인증 오류는 재시도하지 않는다")
    void create_doesNotRetryDatabaseAuthenticationFailure() {
        NotificationCreateCommand command = command();
        CannotCreateTransactionException failure = new CannotCreateTransactionException("cannot begin",
                new SQLInvalidAuthorizationSpecException("access denied", "28000"));
        doThrow(failure).when(notificationCreateTransactionService).save(command);

        assertThatThrownBy(() -> notificationService.create(command)).isSameAs(failure);

        verify(notificationCreateTransactionService, times(1)).save(command);
    }

    @Test
    @DisplayName("중복 선조회 중 DB 연결 실패도 재시도한다")
    void create_retriesDeduplicationLookupFailure() {
        NotificationCreateCommand command = command();
        when(notificationRepository.existsByDeduplicationKey(command.deduplicationKey()))
                .thenThrow(new DataAccessResourceFailureException("connection unavailable")).thenReturn(false);

        notificationService.create(command);

        verify(notificationRepository, times(2)).existsByDeduplicationKey(command.deduplicationKey());
        verify(notificationCreateTransactionService, times(1)).save(command);
    }

    @Test
    @DisplayName("저장 결과가 불명확한 실패 이후 이미 저장된 알림을 다시 만들지 않는다")
    void create_skipsSaveWhenRetryFindsExistingNotification() {
        NotificationCreateCommand command = command();
        when(notificationRepository.existsByDeduplicationKey(command.deduplicationKey())).thenReturn(false, true);
        doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(notificationCreateTransactionService).save(command);

        notificationService.create(command);

        verify(notificationCreateTransactionService, times(1)).save(command);
    }

    @Test
    @DisplayName("재시도 대기 중 인터럽트는 보존하고 추가 저장을 중단한다")
    void create_preservesInterruptionAndStopsRetrying() {
        NotificationCreateCommand command = command();
        doThrow(new CannotAcquireLockException("lock timeout"))
                .when(notificationCreateTransactionService).save(command);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> notificationService.create(command))
                    .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(notificationCreateTransactionService, times(1)).save(command);
        } finally {
            Thread.interrupted();
        }
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
