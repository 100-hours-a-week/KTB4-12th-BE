package com.gift.gift.domain.notification.service;

import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.notification.dto.response.NotificationListItem;
import com.gift.gift.domain.notification.dto.response.NotificationReadResponse;
import com.gift.gift.domain.notification.dto.response.NotificationUnreadCountResponse;
import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.entity.Notification;
import com.gift.gift.domain.notification.query.NotificationPage;
import com.gift.gift.domain.notification.query.NotificationPageAssembler;
import com.gift.gift.domain.notification.repository.NotificationQueryRepository;
import com.gift.gift.domain.notification.repository.NotificationRepository;
import com.gift.gift.domain.notification.support.NotificationCursor;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationQueryRepository notificationQueryRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationCreateTransactionService notificationCreateTransactionService;
    private final NotificationPageAssembler pageAssembler;
    private final OpaqueCursorCodec cursorCodec;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void create(NotificationCreateCommand command) {
        if (notificationRepository.existsByDeduplicationKey(command.deduplicationKey())) {
            log.debug(
                    "알림 중복 생성 요청을 건너뜁니다. notificationType={}, recipientId={}, "
                            + "sourceIdentifier={}, deduplicationKey={}, traceId={}",
                    command.type(),
                    command.recipientId(),
                    command.sourceIdentifier(),
                    command.deduplicationKey(),
                    MDC.get("traceId")
            );
            return;
        }

        try {
            notificationCreateTransactionService.save(command);
        } catch (DataIntegrityViolationException exception) {
            if (!isDeduplicationConflict(exception)) {
                log.error(
                        "알림 저장에 실패했습니다. notificationType={}, recipientId={}, "
                                + "sourceIdentifier={}, deduplicationKey={}, traceId={}, "
                                + "exceptionType={}, exceptionMessage={}",
                        command.type(),
                        command.recipientId(),
                        command.sourceIdentifier(),
                        command.deduplicationKey(),
                        MDC.get("traceId"),
                        exception.getClass().getName(),
                        exception.getMessage(),
                        exception
                );
                throw exception;
            }
            log.debug(
                    "알림 중복 저장 요청을 정상 처리했습니다. notificationType={}, recipientId={}, "
                            + "sourceIdentifier={}, deduplicationKey={}, traceId={}, "
                            + "exceptionType={}, exceptionMessage={}",
                    command.type(),
                    command.recipientId(),
                    command.sourceIdentifier(),
                    command.deduplicationKey(),
                    MDC.get("traceId"),
                    exception.getClass().getName(),
                    exception.getMessage()
            );
        }
    }

    public NotificationUnreadCountResponse getUnreadCount(Long recipientId) {
        return new NotificationUnreadCountResponse(
                notificationQueryRepository.countUnreadNotifications(recipientId)
        );
    }

    public CursorPageResponse<NotificationListItem> getNotifications(
            Long recipientId,
            String rawCursor
    ) {
        NotificationPage page = pageAssembler.assemble(
                notificationQueryRepository.findNotifications(recipientId, decode(rawCursor))
        );
        List<NotificationListItem> items = page.items().stream()
                .map(NotificationListItem::from)
                .toList();

        return CursorPageResponse.from(items, page.nextCursor(), page.hasNext());
    }

    @Transactional
    public NotificationReadResponse markAsRead(Long recipientId, Long notificationId) {
        Notification notification = notificationRepository
                .findByIdAndRecipientIdAndDeletedAtIsNull(notificationId, recipientId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));

        notification.markAsRead(LocalDateTime.now());

        return new NotificationReadResponse(
                notification.getId(),
                notification.isRead(),
                notificationQueryRepository.countUnreadNotifications(recipientId)
        );
    }

    private NotificationCursor decode(String rawCursor) {
        return rawCursor == null ? null : cursorCodec.decode(rawCursor, NotificationCursor.class);
    }

    private boolean isDeduplicationConflict(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation) {
                String constraintName = violation.getConstraintName();
                if (constraintName != null) {
                    int separatorIndex = constraintName.lastIndexOf('.');
                    String unqualifiedName = separatorIndex >= 0
                            ? constraintName.substring(separatorIndex + 1)
                            : constraintName;
                    if ("uk_notifications_deduplication_key".equals(unqualifiedName)) {
                        return true;
                    }
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
