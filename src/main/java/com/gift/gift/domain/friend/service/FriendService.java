package com.gift.gift.domain.friend.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.friend.dto.response.FriendListItem;
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

    public CursorPageResponse<FriendListItem> getFriends(Long userId, String rawCursor) {
        FriendCursor cursor = decode(rawCursor);
        FriendPage page = pageAssembler.assemble(friendQueryRepository.findFriends(userId, cursor));
        List<FriendListItem> items = page.items().stream()
                .map(FriendListItem::from)
                .toList();

        return CursorPageResponse.from(items, page.nextCursor(), page.hasNext());
    }

    private FriendCursor decode(String rawCursor) {
        if (rawCursor == null) {
            return null;
        }

        FriendCursor cursor = cursorCodec.decode(rawCursor, FriendCursor.class);

        // 검색용 커서로 전체 목록을 이어서 조회하면 조회 범위가 어긋나므로 거부한다.
        if (cursor.query() != null) {
            throw new InvalidCursorException();
        }

        return cursor;
    }
}
