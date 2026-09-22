package com.gift.gift.domain.gift.query;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.domain.gift.repository.GiftQueryRow;
import com.gift.gift.domain.gift.support.GiftCursor;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GiftPageAssemblerTest {

    private OpaqueCursorCodec cursorCodec;
    private GiftPageAssembler pageAssembler;

    @BeforeEach
    void setUp() {
        cursorCodec = new OpaqueCursorCodec(JsonMapper.builder().build());
        pageAssembler = new GiftPageAssembler(cursorCodec);
    }

    @Test
    @DisplayName("21건을 조회하면 20건을 반환하고 마지막 응답 항목으로 다음 커서를 생성한다")
    void assemble_returnsFirstTwentyItemsAndNextCursor_whenFetchedTwentyOneItems() {
        List<GiftQueryRow> fetched = rows(21);

        GiftPage page = pageAssembler.assemble(fetched);

        GiftQueryRow lastItem = fetched.get(19);
        GiftCursor nextCursor = cursorCodec.decode(page.nextCursor(), GiftCursor.class);
        assertThat(page.items()).containsExactlyElementsOf(fetched.subList(0, 20));
        assertThat(page.hasNext()).isTrue();
        assertThat(nextCursor).isEqualTo(new GiftCursor(lastItem.completedAt(), lastItem.giftId()));
    }

    @Test
    @DisplayName("조회 결과가 정확히 20건이면 다음 페이지가 없다")
    void assemble_returnsLastPage_whenFetchedTwentyItems() {
        List<GiftQueryRow> fetched = rows(20);

        GiftPage page = pageAssembler.assemble(fetched);

        assertThat(page.items()).containsExactlyElementsOf(fetched);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("조회 결과가 비어 있으면 빈 마지막 페이지를 반환한다")
    void assemble_returnsEmptyLastPage_whenFetchedItemsAreEmpty() {
        GiftPage page = pageAssembler.assemble(List.of());

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

    private List<GiftQueryRow> rows(int count) {
        LocalDateTime firstCompletedAt = LocalDateTime.of(2026, 9, 17, 20, 0);

        return IntStream.range(0, count)
                .mapToObj(index -> new GiftQueryRow(
                        100L - index,
                        firstCompletedAt.minusMinutes(index),
                        1L,
                        "상대 사용자",
                        null,
                        10L,
                        "선물 상품",
                        BigDecimal.valueOf(10_000),
                        1,
                        "브랜드"
                ))
                .toList();
    }
}
