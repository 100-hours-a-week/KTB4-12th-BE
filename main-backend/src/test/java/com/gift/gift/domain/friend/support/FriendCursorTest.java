package com.gift.gift.domain.friend.support;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.global.pagination.InvalidCursorException;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FriendCursorTest {

    private final OpaqueCursorCodec cursorCodec = new OpaqueCursorCodec(JsonMapper.builder().build());

    @Test
    @DisplayName("이름, 이메일, 친구 관계 ID가 유효하면 검색어 없이 커서를 생성한다")
    void friendCursor_createsCursor_whenValuesAreValid() {
        FriendCursor cursor = new FriendCursor("김민지", "minji@example.com", 31L, null);

        assertThat(cursor.name()).isEqualTo("김민지");
        assertThat(cursor.email()).isEqualTo("minji@example.com");
        assertThat(cursor.friendId()).isEqualTo(31L);
        assertThat(cursor.query()).isNull();
    }

    @Test
    @DisplayName("이름이나 이메일이 없거나 친구 관계 ID가 양수가 아니면 커서를 생성할 수 없다")
    void friendCursor_fails_whenValuesAreInvalid() {
        assertThatThrownBy(() -> new FriendCursor(null, "minji@example.com", 31L, null))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> new FriendCursor("김민지", null, 31L, null))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> new FriendCursor("김민지", "minji@example.com", null, null))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> new FriendCursor("김민지", "minji@example.com", 0L, null))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> new FriendCursor("김민지", "minji@example.com", -1L, null))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("검색어가 없는 커서를 인코딩하고 디코딩하면 같은 값이 복원된다")
    void friendCursor_roundTrips_whenQueryIsMissing() {
        FriendCursor cursor = new FriendCursor("김민지", "minji@example.com", 31L, null);

        String encoded = cursorCodec.encode(cursor);

        assertThat(cursorCodec.decode(encoded, FriendCursor.class)).isEqualTo(cursor);
    }

    @Test
    @DisplayName("검색어가 있는 커서를 인코딩하고 디코딩하면 검색어까지 복원된다")
    void friendCursor_roundTrips_whenQueryExists() {
        FriendCursor cursor = new FriendCursor("김민지", "minji@example.com", 31L, "김");

        String encoded = cursorCodec.encode(cursor);

        assertThat(cursorCodec.decode(encoded, FriendCursor.class)).isEqualTo(cursor);
    }

    @Test
    @DisplayName("필수 필드가 빠진 커서를 디코딩하면 INVALID_CURSOR 예외가 발생한다")
    void decode_fails_whenRequiredFieldIsMissing() {
        String encoded = cursorCodec.encode(Map.of("name", "김민지"));

        assertThatThrownBy(() -> cursorCodec.decode(encoded, FriendCursor.class))
                .isInstanceOf(InvalidCursorException.class);
    }
}
