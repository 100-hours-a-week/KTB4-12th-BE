package com.gift.gift.domain.friend.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.friend.dto.response.FriendListItem;
import com.gift.gift.domain.friend.exception.FriendErrorCode;
import com.gift.gift.domain.friend.exception.FriendException;
import com.gift.gift.domain.friend.query.FriendPage;
import com.gift.gift.domain.friend.query.FriendPageAssembler;
import com.gift.gift.domain.friend.repository.FriendQueryRepository;
import com.gift.gift.domain.friend.support.FriendCursor;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.pagination.InvalidCursorException;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FriendService {

    private final FriendQueryRepository friendQueryRepository;
    private final OpaqueCursorCodec cursorCodec;
    private final FriendPageAssembler pageAssembler;

    public CursorPageResponse<FriendListItem> getFriends(
            Long userId,
            String rawCursor
    ) {
        FriendCursor cursor = decodeListCursor(rawCursor);

        try {
            FriendPage page = pageAssembler.assemble(
                    friendQueryRepository.findFriends(
                            userId,
                            cursor
                    )
            );

            return toPageResponse(page);
        } catch (RuntimeException exception) {
            throw new FriendException(
                    FriendErrorCode.FRIEND_LIST_RETRIEVAL_FAILED,
                    exception
            );
        }
    }

    public CursorPageResponse<FriendListItem> searchFriends(
            Long userId,
            String query,
            String rawCursor
    ) {
        validateSearchQuery(query);

        FriendCursor cursor = decodeSearchCursor(
                rawCursor,
                query
        );

        try {
            FriendPage page = pageAssembler.assemble(
                    friendQueryRepository.findFriendsByName(
                            userId,
                            query,
                            cursor
                    ),
                    query
            );

            return toPageResponse(page);
        } catch (RuntimeException exception) {
            throw new FriendException(
                    FriendErrorCode.FRIEND_SEARCH_FAILED,
                    exception
            );
        }
    }

    private CursorPageResponse<FriendListItem> toPageResponse(FriendPage page) {

        List<FriendListItem> items = page.items()
                .stream()
                .map(FriendListItem::from)
                .toList();

        return CursorPageResponse.from(
                items,
                page.nextCursor(),
                page.hasNext()
        );
    }

    private void validateSearchQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new FriendException(
                    FriendErrorCode.FRIEND_SEARCH_QUERY_REQUIRED
            );
        }
    }

    private FriendCursor decodeListCursor(String rawCursor) {
        if (rawCursor == null) {
            return null;
        }

        FriendCursor cursor = cursorCodec.decode(
                rawCursor,
                FriendCursor.class
        );

        if (cursor.query() != null) {
            throw new InvalidCursorException();
        }

        return cursor;
    }

    private FriendCursor decodeSearchCursor(String rawCursor, String query) {

        if (rawCursor == null) {
            return null;
        }

        FriendCursor cursor = cursorCodec.decode(rawCursor, FriendCursor.class);

        if (!query.equals(cursor.query())) {
            throw new InvalidCursorException();
        }

        return cursor;
    }

}
