package com.gift.gift.domain.notification.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.notification.dto.response.NotificationListItem;
import com.gift.gift.domain.notification.dto.response.NotificationUnreadCountResponse;
import com.gift.gift.domain.notification.query.NotificationPage;
import com.gift.gift.domain.notification.query.NotificationPageAssembler;
import com.gift.gift.domain.notification.repository.NotificationQueryRepository;
import com.gift.gift.domain.notification.support.NotificationCursor;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationQueryRepository notificationQueryRepository;
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

    private NotificationCursor decode(String rawCursor) {
        return rawCursor == null ? null : cursorCodec.decode(rawCursor, NotificationCursor.class);
    }
}
