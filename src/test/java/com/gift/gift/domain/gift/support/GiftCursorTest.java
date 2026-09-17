package com.gift.gift.domain.gift.support;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GiftCursorTest {

    @Test
    @DisplayName("커서의 완료 시각이나 선물 ID가 유효하지 않으면 생성할 수 없다")
    void giftCursor_fails_whenValuesAreInvalid() {
        assertThatThrownBy(() -> new GiftCursor(null, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GiftCursor(LocalDateTime.now(), 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
