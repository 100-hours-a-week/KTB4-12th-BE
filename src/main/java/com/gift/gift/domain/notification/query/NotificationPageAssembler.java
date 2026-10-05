package com.gift.gift.domain.notification.query;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.gift.gift.domain.notification.repository.NotificationQueryRow;
import com.gift.gift.domain.notification.support.NotificationCursor;
import com.gift.gift.global.common.PaginationPolicy;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

@Component
public class NotificationPageAssembler {

    private final OpaqueCursorCodec cursorCodec;

    public NotificationPageAssembler(OpaqueCursorCodec cursorCodec) {
        this.cursorCodec = cursorCodec;
    }

    public NotificationPage assemble(List<NotificationQueryRow> fetched) {
        Objects.requireNonNull(fetched, "알림 조회 결과는 필수입니다.");

        if (fetched.size() > PaginationPolicy.CURSOR_FETCH_SIZE) {
            throw new IllegalArgumentException("알림 페이지 조회 결과는 최대 21건이어야 합니다.");
        }

        boolean hasNext = fetched.size() > PaginationPolicy.DEFAULT_PAGE_SIZE;
        List<NotificationQueryRow> items = List.copyOf(fetched.subList(
                0,
                Math.min(fetched.size(), PaginationPolicy.DEFAULT_PAGE_SIZE)
        ));

        if (!hasNext) {
            return new NotificationPage(items, false, null);
        }

        NotificationQueryRow lastItem = items.getLast();
        NotificationCursor nextCursor = new NotificationCursor(
                lastItem.notifiedAt(),
                lastItem.notificationId()
        );

        return new NotificationPage(items, true, cursorCodec.encode(nextCursor));
    }
}
