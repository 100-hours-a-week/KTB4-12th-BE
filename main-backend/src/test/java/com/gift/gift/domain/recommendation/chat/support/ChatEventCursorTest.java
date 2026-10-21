package com.gift.gift.domain.recommendation.chat.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.LinkedMultiValueMap;

import com.gift.gift.domain.recommendation.chat.exception.ChatException;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatEventCursorTest {

    @Test
    @DisplayName("cursor를 생략하면 0을 사용한다")
    void parse_returnsZero_whenCursorIsAbsent() {
        LinkedMultiValueMap<String, String> query =
                new LinkedMultiValueMap<>();

        assertThat(ChatEventCursor.parse(query)).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 15, Long.MAX_VALUE})
    @DisplayName("0 이상 Long 범위의 cursor를 허용한다")
    void parse_acceptsValidCursor(long cursor) {
        LinkedMultiValueMap<String, String> query =
                new LinkedMultiValueMap<>();

        query.add("afterEventId", Long.toString(cursor));

        assertThat(ChatEventCursor.parse(query))
                .isEqualTo(cursor);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "-1",
            "+1",
            "1.0",
            " 1",
            "1 ",
            "1e2",
            "abc",
            "9223372036854775808"
    })
    @DisplayName("잘못된 cursor는 INVALID_CURSOR로 거절한다")
    void parse_rejectsInvalidCursor(String cursor) {
        LinkedMultiValueMap<String, String> query =
                new LinkedMultiValueMap<>();

        query.add("afterEventId", cursor);

        assertThatThrownBy(() -> ChatEventCursor.parse(query))
                .isInstanceOfSatisfying(
                        ChatException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_CURSOR)
                );
    }

    @Test
    @DisplayName("같은 cursor Query를 두 번 전달하면 거절한다")
    void parse_rejectsDuplicateCursor() {
        LinkedMultiValueMap<String, String> query =
                new LinkedMultiValueMap<>();

        query.add("afterEventId", "1");
        query.add("afterEventId", "2");

        assertThatThrownBy(() -> ChatEventCursor.parse(query))
                .isInstanceOfSatisfying(
                        ChatException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_CURSOR)
                );
    }

    @Test
    @DisplayName("허용하지 않은 Query는 INVALID_REQUEST로 거절한다")
    void parse_rejectsUnknownQuery() {
        LinkedMultiValueMap<String, String> query =
                new LinkedMultiValueMap<>();

        query.add("afterEventId", "0");
        query.add("size", "20");

        assertThatThrownBy(() -> ChatEventCursor.parse(query))
                .isInstanceOfSatisfying(
                        ChatException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_REQUEST)
                );
    }
}
