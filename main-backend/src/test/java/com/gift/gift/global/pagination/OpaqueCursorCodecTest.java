package com.gift.gift.global.pagination;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.domain.gift.support.GiftCursor;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpaqueCursorCodecTest {

    private OpaqueCursorCodec cursorCodec;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        cursorCodec = new OpaqueCursorCodec(objectMapper);
    }

    @Test
    @DisplayName("도메인 커서를 URL-safe Base64 JSON으로 인코딩하고 디코딩한다")
    void cursorCodec_roundTripsDomainCursor() {
        GiftCursor cursor = new GiftCursor(LocalDateTime.of(2026, 9, 17, 14, 30), 10L);

        String encoded = cursorCodec.encode(cursor);
        GiftCursor decoded = cursorCodec.decode(encoded, GiftCursor.class);

        assertThat(encoded).doesNotContain("=");
        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    @DisplayName("Base64 또는 JSON 형식이 잘못된 커서는 INVALID_CURSOR로 처리한다")
    void cursorCodec_fails_whenCursorIsMalformed() {
        assertThatThrownBy(() -> cursorCodec.decode("잘못된-커서", GiftCursor.class))
                .isInstanceOf(InvalidCursorException.class)
                .satisfies(exception -> assertThat(((InvalidCursorException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CURSOR));
    }

    @Test
    @DisplayName("알 수 없는 필드가 포함된 커서는 거부한다")
    void cursorCodec_fails_whenCursorContainsUnknownField() {
        String encoded = cursorCodec.encode(new CursorWithUnknownField(
                LocalDateTime.of(2026, 9, 17, 14, 30),
                10L,
                "unexpected"
        ));

        assertThatThrownBy(() -> cursorCodec.decode(encoded, GiftCursor.class))
                .isInstanceOf(InvalidCursorException.class);
    }

    private record CursorWithUnknownField(
            LocalDateTime completedAt,
            Long id,
            String unknown
    ) {
    }
}
