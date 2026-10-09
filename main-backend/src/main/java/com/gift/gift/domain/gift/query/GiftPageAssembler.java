package com.gift.gift.domain.gift.query;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.gift.gift.domain.gift.repository.GiftQueryRow;
import com.gift.gift.domain.gift.support.GiftCursor;
import com.gift.gift.global.common.PaginationPolicy;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

@Component
public class GiftPageAssembler {

    private final OpaqueCursorCodec cursorCodec;

    public GiftPageAssembler(OpaqueCursorCodec cursorCodec) {
        this.cursorCodec = cursorCodec;
    }

    public GiftPage assemble(List<GiftQueryRow> fetched) {
        Objects.requireNonNull(fetched, "선물 조회 결과는 필수입니다.");

        if (fetched.size() > PaginationPolicy.CURSOR_FETCH_SIZE) {
            throw new IllegalArgumentException("선물 페이지 조회 결과는 최대 21건이어야 합니다.");
        }

        boolean hasNext = fetched.size() > PaginationPolicy.DEFAULT_PAGE_SIZE;
        List<GiftQueryRow> items = List.copyOf(fetched.subList(
                0,
                Math.min(fetched.size(), PaginationPolicy.DEFAULT_PAGE_SIZE)
        ));

        if (!hasNext) {
            return new GiftPage(items, false, null);
        }

        GiftQueryRow lastItem = items.getLast();
        GiftCursor nextCursor = new GiftCursor(lastItem.completedAt(), lastItem.giftId());

        return new GiftPage(items, true, cursorCodec.encode(nextCursor));
    }
}
