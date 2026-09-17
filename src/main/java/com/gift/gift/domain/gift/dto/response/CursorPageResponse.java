package com.gift.gift.domain.gift.dto.response;

import java.util.List;

public record CursorPageResponse<T>(
        List<T> items,
        Pagination pagination
) {

    public CursorPageResponse {
        items = List.copyOf(items);
    }

    public static <T> CursorPageResponse<T> from(List<T> items, String nextCursor, boolean hasNext) {
        return new CursorPageResponse<>(items, new Pagination(nextCursor, hasNext));
    }

    public record Pagination(
            String nextCursor,
            boolean hasNext
    ) {
    }
}
