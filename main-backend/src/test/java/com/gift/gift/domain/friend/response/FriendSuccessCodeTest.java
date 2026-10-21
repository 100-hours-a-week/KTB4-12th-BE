package com.gift.gift.domain.friend.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FriendSuccessCodeTest {

    @Test
    @DisplayName("친구 목록 조회 성공 코드는 기존 상태와 메시지를 유지한다")
    void friendListRetrieved_preservesExistingApiContract() {
        assertEquals(
                HttpStatus.OK,
                FriendSuccessCode.FRIEND_LIST_RETRIEVED.status()
        );
        assertEquals(
                "친구 목록을 조회했습니다.",
                FriendSuccessCode.FRIEND_LIST_RETRIEVED.message()
        );
    }

}
