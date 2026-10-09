package com.gift.gift.domain.notification.query;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.notification.entity.NotificationType;
import com.gift.gift.domain.notification.repository.NotificationQueryRow;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationPageAssemblerTest {

    private OpaqueCursorCodec cursorCodec;
    private NotificationPageAssembler pageAssembler;

    @BeforeEach
    void setUp() {
        cursorCodec = mock(OpaqueCursorCodec.class);
        pageAssembler = new NotificationPageAssembler(cursorCodec);
    }

    @Test
    @DisplayName("21건을 받으면 20건과 다음 커서를 반환한다")
    void assemble_returnsTwentyItemsAndNextCursor_whenFetchedTwentyOneItems() {
        List<NotificationQueryRow> rows = rows(21);
        when(cursorCodec.encode(any())).thenReturn("next-cursor");

        NotificationPage page = pageAssembler.assemble(rows);

        assertThat(page.items()).hasSize(20);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("next-cursor");
    }

    @Test
    @DisplayName("20건 이하이면 다음 커서를 반환하지 않는다")
    void assemble_returnsNoNextCursor_whenFetchedAtMostTwentyItems() {
        NotificationPage page = pageAssembler.assemble(rows(20));

        assertThat(page.items()).hasSize(20);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    private List<NotificationQueryRow> rows(int size) {
        return java.util.stream.IntStream.rangeClosed(1, size)
                .mapToObj(id -> new NotificationQueryRow(
                        (long) id,
                        NotificationType.GIFT_RECEIVED,
                        "선물 도착",
                        "새 선물이 도착했습니다.",
                        null,
                        null,
                        LocalDateTime.of(2026, 10, 5, 12, 0).minusMinutes(id),
                        null
                ))
                .toList();
    }
}
