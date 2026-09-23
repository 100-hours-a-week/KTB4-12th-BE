package com.gift.gift.domain.gift.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.global.exception.ErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GiftErrorCodeTest {

    @Test
    @DisplayName("자기 자신에게 선물하면 발생하는 오류는 GIFT_CANNOT_SEND_TO_SELF 계약을 유지한다")
    void giftCannotSendToSelf_preservesExistingApiContract() {
        assertEquals(
                ErrorCode.GIFT_CANNOT_SEND_TO_SELF,
                GiftErrorCode.GIFT_CANNOT_SEND_TO_SELF.errorCode()
        );
        assertEquals(
                "자기 자신에게는 선물을 보낼 수 없습니다.",
                GiftErrorCode.GIFT_CANNOT_SEND_TO_SELF.message()
        );
    }
}
