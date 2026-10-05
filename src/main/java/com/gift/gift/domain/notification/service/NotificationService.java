package com.gift.gift.domain.notification.service;

import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.notification.dto.response.NotificationListItem;
import com.gift.gift.domain.notification.dto.response.NotificationReadResponse;
import com.gift.gift.domain.notification.dto.response.NotificationUnreadCountResponse;
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
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationQueryRepository notificationQueryRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationPageAssembler pageAssembler;
    private final OpaqueCursorCodec cursorCodec;

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
