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

    @Test
    @DisplayName("친구 추가 성공 코드는 대상 이름을 포함한 메시지를 생성한다")
    void friendAdded_formatsFriendName() {
        assertEquals(
                HttpStatus.CREATED,
                FriendSuccessCode.FRIEND_ADDED.status()
        );
        assertEquals(
                "김민지님을 친구로 추가했습니다.",
                FriendSuccessCode.FRIEND_ADDED.formatMessage(
                        "김민지"
                )
        );
    }
}
