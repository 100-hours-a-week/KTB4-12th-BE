package com.gift.gift.domain.friend.query;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.domain.friend.repository.FriendQueryRow;
import com.gift.gift.domain.friend.support.FriendCursor;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FriendPageAssemblerTest {

    private OpaqueCursorCodec cursorCodec;
    private FriendPageAssembler pageAssembler;

    @BeforeEach
    void setUp() {
        cursorCodec = new OpaqueCursorCodec(JsonMapper.builder().build());
        pageAssembler = new FriendPageAssembler(cursorCodec);
    }

    @Test
    @DisplayName("21건을 조회하면 20건을 반환하고 마지막 응답 항목으로 검색어 없는 다음 커서를 생성한다")
    void assemble_returnsFirstTwentyItemsAndNextCursor_whenFetchedTwentyOneItems() {
        List<FriendQueryRow> fetched = rows(21);

        FriendPage page = pageAssembler.assemble(fetched);

        FriendQueryRow lastItem = fetched.get(19);
        FriendCursor nextCursor = cursorCodec.decode(page.nextCursor(), FriendCursor.class);
        assertThat(page.items()).containsExactlyElementsOf(fetched.subList(0, 20));
        assertThat(page.hasNext()).isTrue();
        assertThat(nextCursor).isEqualTo(
                new FriendCursor(lastItem.name(), lastItem.email(), lastItem.friendId(), null)
        );
    }

    @Test
    @DisplayName("조회 결과가 정확히 20건이면 다음 페이지가 없다")
    void assemble_returnsLastPage_whenFetchedTwentyItems() {
        List<FriendQueryRow> fetched = rows(20);

        FriendPage page = pageAssembler.assemble(fetched);

        assertThat(page.items()).containsExactlyElementsOf(fetched);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("조회 결과가 비어 있으면 빈 마지막 페이지를 반환한다")
    void assemble_returnsEmptyLastPage_whenFetchedItemsAreEmpty() {
        FriendPage page = pageAssembler.assemble(List.of());

        assertThat(page.items()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("Repository 조회 한도인 21건을 초과한 결과는 거부한다")
    void assemble_throwsException_whenFetchedItemsExceedLimit() {
        assertThatThrownBy(() -> pageAssembler.assemble(rows(22)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<FriendQueryRow> rows(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> new FriendQueryRow(
                        index + 1L,
                        1000L + index,
                        "친구" + index,
                        "friend" + index + "@example.com",
                        LocalDate.of(2000, 1, 1),
                        false
                ))
                .toList();
    }
}
