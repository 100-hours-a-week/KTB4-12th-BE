package com.gift.gift.domain.friend.query;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.gift.gift.domain.friend.repository.FriendQueryRow;
import com.gift.gift.domain.friend.support.FriendCursor;
import com.gift.gift.global.common.PaginationPolicy;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

@Component
public class FriendPageAssembler {

    private final OpaqueCursorCodec cursorCodec;

    public FriendPageAssembler(OpaqueCursorCodec cursorCodec) {
        this.cursorCodec = cursorCodec;
    }

    public FriendPage assemble(List<FriendQueryRow> fetched) {
        Objects.requireNonNull(fetched, "친구 조회 결과는 필수입니다.");

        if (fetched.size() > PaginationPolicy.CURSOR_FETCH_SIZE) {
            throw new IllegalArgumentException("친구 페이지 조회 결과는 최대 21건이어야 합니다.");
        }

        boolean hasNext = fetched.size() > PaginationPolicy.DEFAULT_PAGE_SIZE;
        List<FriendQueryRow> items = List.copyOf(fetched.subList(
                0,
                Math.min(fetched.size(), PaginationPolicy.DEFAULT_PAGE_SIZE)
        ));

        if (!hasNext) {
            return new FriendPage(items, false, null);
        }

        FriendQueryRow lastItem = items.getLast();
        FriendCursor nextCursor = new FriendCursor(
                lastItem.name(),
                lastItem.email(),
                lastItem.friendId(),
                null
        );

        return new FriendPage(items, true, cursorCodec.encode(nextCursor));
    }
}
