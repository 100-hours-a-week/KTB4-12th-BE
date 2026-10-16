package com.gift.gift.domain.notification.service;

import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

import static com.gift.gift.domain.notification.support.NotificationPersistenceFailures.isDeduplicationConflict;
import static com.gift.gift.domain.notification.support.NotificationPersistenceFailures.isRetryable;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class NotificationService {

    private static final int MAX_CREATE_ATTEMPTS = 3;

    private final NotificationQueryRepository notificationQueryRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationCreateTransactionService notificationCreateTransactionService;
    private final NotificationPageAssembler pageAssembler;
    private final OpaqueCursorCodec cursorCodec;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void create(NotificationCreateCommand command) {
        // shortcut: 재시도 중 프로세스가 종료되면 유실된다. 재시작 복구가 필요하면 영속 작업으로 전환한다.
        for (int attempt = 1; attempt <= MAX_CREATE_ATTEMPTS; attempt++) {
            try {
                createOnce(command);
                return;
            } catch (RuntimeException exception) {
                if (!isRetryable(exception)) {
                    throw exception;
                }
                if (attempt == MAX_CREATE_ATTEMPTS) {
                    log.error("알림 저장 재시도를 소진했습니다. deduplicationKey={}, attempts={}, traceId={}",
                            command.deduplicationKey(), attempt, MDC.get("traceId"), exception);
                    throw exception;
                }
                long delayMillis = attempt == 1 ? 200L : 500L;
                log.warn("알림 저장을 재시도합니다. deduplicationKey={}, attempt={}, delayMillis={}, traceId={}",
                        command.deduplicationKey(), attempt, delayMillis, MDC.get("traceId"));
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("알림 저장 재시도가 중단됐습니다.", interrupted);
                }
            }
        }
    }

    private void createOnce(NotificationCreateCommand command) {
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
}
