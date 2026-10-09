package com.gift.gift.domain.friend.service;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.gift.gift.domain.friend.dto.response.FriendListItem;
import com.gift.gift.domain.friend.exception.FriendErrorCode;
import com.gift.gift.domain.friend.exception.FriendException;
import com.gift.gift.domain.friend.query.FriendPage;
import com.gift.gift.domain.friend.query.FriendPageAssembler;
import com.gift.gift.domain.friend.repository.FriendQueryRepository;
import com.gift.gift.domain.friend.repository.FriendQueryRow;
import com.gift.gift.domain.friend.repository.FriendRepository;
import com.gift.gift.domain.friend.support.FriendCursor;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.pagination.InvalidCursorException;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FriendSearchServiceTest {

    private static final Long USER_ID = 1L;

    private FriendQueryRepository friendQueryRepository;
    private FriendRepository friendRepository;
    private UserRepository userRepository;
    private OpaqueCursorCodec cursorCodec;
    private FriendPageAssembler pageAssembler;
    private FriendService friendService;

    @BeforeEach
    void setUp() {
        friendQueryRepository =
                mock(FriendQueryRepository.class);

        friendRepository =
                mock(FriendRepository.class);

        userRepository =
                mock(UserRepository.class);

        cursorCodec =
                mock(OpaqueCursorCodec.class);

        pageAssembler =
                mock(FriendPageAssembler.class);

        friendService = new FriendService(
                friendQueryRepository,
                friendRepository,
                userRepository,
                cursorCodec,
                pageAssembler
        );
    }

    @Test
    @DisplayName(
            "커서가 없으면 검색 첫 페이지를 조회한다"
    )
    void searchFriends_returnsFirstPage() {
        FriendQueryRow row = row();

        List<FriendQueryRow> rows = List.of(row);

        when(friendQueryRepository.findFriendsByName(
                USER_ID,
                "김",
                null
        )).thenReturn(rows);

        when(pageAssembler.assemble(
                rows,
                "김"
        )).thenReturn(
                new FriendPage(
                        rows,
                        true,
                        "next-cursor"
                )
        );

        CursorPageResponse<FriendListItem> result =
                friendService.searchFriends(
                        USER_ID,
                        "김",
                        null
                );

        assertThat(result.items()).containsExactly(
                new FriendListItem(
                        31L,
                        27L,
                        "김민지",
                        "minji@example.com",
                        "2000-03-14"
                )
        );

        assertThat(result.pagination().nextCursor())
                .isEqualTo("next-cursor");

        assertThat(result.pagination().hasNext())
                .isTrue();

        verify(friendQueryRepository).findFriendsByName(
                USER_ID,
                "김",
                null
        );

        verify(pageAssembler).assemble(
                rows,
                "김"
        );

        verifyNoInteractions(cursorCodec);
    }

    @Test
    @DisplayName(
            "동일한 검색어의 커서를 디코딩하여 다음 페이지를 조회한다"
    )
    void searchFriends_usesCursorForSameQuery() {
        FriendCursor cursor = new FriendCursor(
                "김민지",
                "minji@example.com",
                31L,
                "김"
        );

        when(cursorCodec.decode(
                "encoded-cursor",
                FriendCursor.class
        )).thenReturn(cursor);

        when(friendQueryRepository.findFriendsByName(
                USER_ID,
                "김",
                cursor
        )).thenReturn(List.of());

        when(pageAssembler.assemble(
                List.of(),
                "김"
        )).thenReturn(
                new FriendPage(
                        List.of(),
                        false,
                        null
                )
        );

        CursorPageResponse<FriendListItem> result =
                friendService.searchFriends(
                        USER_ID,
                        "김",
                        "encoded-cursor"
                );

        assertThat(result.items()).isEmpty();
        assertThat(result.pagination().nextCursor())
                .isNull();
        assertThat(result.pagination().hasNext())
                .isFalse();

        verify(cursorCodec).decode(
                "encoded-cursor",
                FriendCursor.class
        );

        verify(friendQueryRepository).findFriendsByName(
                USER_ID,
                "김",
                cursor
        );
    }

    @Test
    @DisplayName(
            "현재 검색어와 커서 검색어가 다르면 조회하지 않고 INVALID_CURSOR를 발생시킨다"
    )
    void searchFriends_rejectsCursorForDifferentQuery() {
        FriendCursor cursor = new FriendCursor(
                "김민지",
                "minji@example.com",
                31L,
                "이"
        );

        when(cursorCodec.decode(
                "encoded-cursor",
                FriendCursor.class
        )).thenReturn(cursor);

        assertThatThrownBy(() ->
                friendService.searchFriends(
                        USER_ID,
                        "김",
                        "encoded-cursor"
                )
        )
                .isInstanceOf(InvalidCursorException.class)
                .hasMessage("페이지 정보를 확인해 주세요.");

        verify(cursorCodec).decode(
                "encoded-cursor",
                FriendCursor.class
        );

        verifyNoInteractions(
                friendQueryRepository,
                pageAssembler
        );
    }

    @Test
    @DisplayName(
            "커서 디코딩에 실패하면 Repository를 호출하지 않는다"
    )
    void searchFriends_propagatesInvalidCursor() {
        InvalidCursorException exception =
                new InvalidCursorException();

        when(cursorCodec.decode(
                "broken",
                FriendCursor.class
        )).thenThrow(exception);

        assertThatThrownBy(() ->
                friendService.searchFriends(
                        USER_ID,
                        "김",
                        "broken"
                )
        )
                .isSameAs(exception)
                .hasMessage("페이지 정보를 확인해 주세요.");

        verify(cursorCodec).decode(
                "broken",
                FriendCursor.class
        );

        verifyNoInteractions(
                friendQueryRepository,
                pageAssembler
        );
    }

    @ParameterizedTest(name = "[{index}] query=\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "   "
    })
    @DisplayName(
            "검색어가 없거나 공백이면 조회하지 않고 INVALID_REQUEST를 발생시킨다"
    )
    void searchFriends_rejectsBlankQuery(
            String query
    ) {
        assertThatThrownBy(() ->
                friendService.searchFriends(
                        USER_ID,
                        query,
                        null
                )
        )
                .isInstanceOfSatisfying(
                        FriendException.class,
                        exception -> {
                            assertThat(
                                    exception.getErrorCode()
                            ).isEqualTo(
                                    ErrorCode.INVALID_REQUEST
                            );

                            assertThat(
                                    exception.getMessage()
                            ).isEqualTo(
                                    "검색어를 입력해 주세요."
                            );
                        }
                );

        verifyNoInteractions(
                cursorCodec,
                friendQueryRepository,
                pageAssembler
        );
    }

    @Test
    @DisplayName(
            "친구 검색 조회 중 내부 오류가 발생하면 검색 실패 예외로 변환한다"
    )
    void searchFriends_wrapsRepositoryFailure() {
        IllegalStateException cause =
                new IllegalStateException(
                        "테스트용 Repository 오류"
                );

        when(friendQueryRepository.findFriendsByName(
                USER_ID,
                "김",
                null
        )).thenThrow(cause);

        assertThatThrownBy(() ->
                friendService.searchFriends(
                        USER_ID,
                        "김",
                        null
                )
        )
                .isInstanceOfSatisfying(
                        FriendException.class,
                        exception -> {
                            assertThat(
                                    exception.getErrorCode()
                            ).isEqualTo(
                                    ErrorCode.INTERNAL_SERVER_ERROR
                            );

                            assertThat(
                                    exception.getMessage()
                            ).isEqualTo(
                                    "친구 검색에 실패했습니다. "
                                            + "다시 시도해 주세요."
                            );

                            assertThat(
                                    exception.getCause()
                            ).isSameAs(cause);
                        }
                );

        verifyNoInteractions(pageAssembler);
    }

    private FriendQueryRow row() {
        return new FriendQueryRow(
                31L,
                27L,
                "김민지",
                "minji@example.com",
                LocalDate.of(2000, 3, 14),
                true
        );
    }
}
