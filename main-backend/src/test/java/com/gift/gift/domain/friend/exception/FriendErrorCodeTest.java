package com.gift.gift.domain.friend.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.global.exception.ErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FriendErrorCodeTest {

    @Test
    @DisplayName("친구 오류 코드는 기존 외부 오류 계약을 유지한다")
    void friendErrorCode_preservesExistingApiContract() {
        assertEquals(
                ErrorCode.FRIEND_ALREADY_EXISTS,
                FriendErrorCode.FRIEND_ALREADY_EXISTS.errorCode()
        );
        assertEquals(
                "이미 등록된 친구입니다.",
                FriendErrorCode.FRIEND_ALREADY_EXISTS.message()
        );
        assertEquals(
                ErrorCode.FRIEND_CANNOT_ADD_SELF,
                FriendErrorCode.FRIEND_CANNOT_ADD_SELF.errorCode()
        );
        assertEquals(
                "본인은 친구로 추가할 수 없습니다.",
                FriendErrorCode.FRIEND_CANNOT_ADD_SELF.message()
        );
    }
}
