package com.gift.gift.domain.friend.service;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.domain.friend.dto.response.FriendListItem;
import com.gift.gift.domain.friend.query.FriendPage;
import com.gift.gift.domain.friend.query.FriendPageAssembler;
import com.gift.gift.domain.friend.repository.FriendQueryRepository;
import com.gift.gift.domain.friend.repository.FriendQueryRow;
import com.gift.gift.domain.friend.repository.FriendRepository;
import com.gift.gift.domain.friend.support.FriendCursor;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.pagination.InvalidCursorException;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FriendServiceTest {

    private static final Long USER_ID = 1L;

    private FriendQueryRepository friendQueryRepository;
    private FriendRepository friendRepository;
    private UserRepository userRepository;
    private OpaqueCursorCodec cursorCodec;
    private FriendPageAssembler pageAssembler;
    private FriendService friendService;

    @BeforeEach
    void setUp() {
        friendQueryRepository = mock(FriendQueryRepository.class);
        friendRepository = mock(FriendRepository.class);
        userRepository = mock(UserRepository.class);
        cursorCodec = mock(OpaqueCursorCodec.class);
        pageAssembler = mock(FriendPageAssembler.class);
        friendService = new FriendService(
                friendQueryRepository,
                friendRepository,
                userRepository,
                cursorCodec,
                pageAssembler
        );
    }

    @Test
    @DisplayName("커서가 없으면 첫 페이지를 조회하고 응답 항목과 페이지 정보를 반환한다")
    void getFriends_returnsFirstPage_whenCursorIsMissing() {
        FriendQueryRow row = row();
        when(friendQueryRepository.findFriends(USER_ID, null)).thenReturn(List.of(row));
        when(pageAssembler.assemble(List.of(row))).thenReturn(new FriendPage(List.of(row), true, "next"));

        CursorPageResponse<FriendListItem> result = friendService.getFriends(USER_ID, null);

        assertThat(result.items()).containsExactly(
                new FriendListItem(31L, 27L, "김민지", "minji@example.com", "2000-03-14")
        );
        assertThat(result.pagination().hasNext()).isTrue();
        assertThat(result.pagination().nextCursor()).isEqualTo("next");
        verifyNoInteractions(cursorCodec);
    }

    @Test
    @DisplayName("커서가 있으면 디코딩한 값으로 다음 페이지를 조회한다")
    void getFriends_decodesCursorAndQueriesWithIt() {
        String rawCursor = "cursor";
        FriendCursor cursor = new FriendCursor("김민지", "minji@example.com", 31L, null);
        when(cursorCodec.decode(rawCursor, FriendCursor.class)).thenReturn(cursor);
        when(friendQueryRepository.findFriends(USER_ID, cursor)).thenReturn(List.of());
        when(pageAssembler.assemble(List.of())).thenReturn(new FriendPage(List.of(), false, null));

        friendService.getFriends(USER_ID, rawCursor);

        verify(friendQueryRepository).findFriends(USER_ID, cursor);
    }

    @Test
    @DisplayName("조회 결과가 없으면 빈 목록과 마지막 페이지 정보를 반환한다")
    void getFriends_returnsEmptyLastPage_whenNoFriends() {
        when(friendQueryRepository.findFriends(USER_ID, null)).thenReturn(List.of());
        when(pageAssembler.assemble(List.of())).thenReturn(new FriendPage(List.of(), false, null));

        CursorPageResponse<FriendListItem> result = friendService.getFriends(USER_ID, null);

        assertThat(result.items()).isEmpty();
        assertThat(result.pagination().hasNext()).isFalse();
        assertThat(result.pagination().nextCursor()).isNull();
    }

    @Test
    @DisplayName("검색어가 담긴 커서를 전체 목록 조회에 사용하면 조회하지 않고 INVALID_CURSOR 예외가 발생한다")
    void getFriends_throwsInvalidCursor_whenCursorHasQuery() {
        FriendCursor searchCursor = new FriendCursor("김민지", "minji@example.com", 31L, "김");
        when(cursorCodec.decode("cursor", FriendCursor.class)).thenReturn(searchCursor);

        assertThatThrownBy(() -> friendService.getFriends(USER_ID, "cursor"))
                .isInstanceOf(InvalidCursorException.class);
        verifyNoInteractions(friendQueryRepository);
    }

    @Test
    @DisplayName("커서 디코딩에 실패하면 조회하지 않고 INVALID_CURSOR 예외를 그대로 전달한다")
    void getFriends_propagatesInvalidCursor_whenDecodingFails() {
        when(cursorCodec.decode("broken", FriendCursor.class)).thenThrow(new InvalidCursorException());

        assertThatThrownBy(() -> friendService.getFriends(USER_ID, "broken"))
                .isInstanceOf(InvalidCursorException.class);
        verifyNoInteractions(friendQueryRepository);
    }

    @Test
    @DisplayName("커서가 빈 문자열이면 첫 페이지로 취급하지 않고 INVALID_CURSOR 예외가 발생한다")
    void getFriends_throwsInvalidCursor_whenCursorIsBlank() {
        FriendService serviceWithRealCodec = new FriendService(
                friendQueryRepository,
                friendRepository,
                userRepository,
                new OpaqueCursorCodec(JsonMapper.builder().build()),
                pageAssembler
        );

        assertThatThrownBy(() -> serviceWithRealCodec.getFriends(USER_ID, ""))
                .isInstanceOf(InvalidCursorException.class);
        verifyNoInteractions(friendQueryRepository);
    }

    private FriendQueryRow row() {
        return new FriendQueryRow(31L, 27L, "김민지", "minji@example.com", LocalDate.of(2000, 3, 14), true);
    }
}
