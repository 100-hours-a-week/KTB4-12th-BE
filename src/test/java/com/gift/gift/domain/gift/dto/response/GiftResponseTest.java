package com.gift.gift.domain.gift.dto.response;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.global.pagination.CursorPageResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GiftResponseTest {

    @Test
    @DisplayName("커서 페이지 응답은 목록과 다음 페이지 정보를 생성한다")
    void cursorPageResponse_createsPagination() {
        CursorPageResponse<String> response = CursorPageResponse.from(
                List.of("gift"),
                "next-cursor",
                true
        );

        assertThat(response.items()).containsExactly("gift");
        assertThat(response.pagination().nextCursor()).isEqualTo("next-cursor");
        assertThat(response.pagination().hasNext()).isTrue();
    }

    @Test
    @DisplayName("커서 페이지 응답은 전달받은 목록의 변경에 영향을 받지 않는다")
    void cursorPageResponse_copiesItemsDefensively() {
        List<String> items = new ArrayList<>(List.of("gift"));

        CursorPageResponse<String> response = CursorPageResponse.from(items, null, false);
        items.add("another-gift");

        assertThat(response.items()).containsExactly("gift");
        assertThatThrownBy(() -> response.items().add("new-gift"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
