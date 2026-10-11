package com.gift.gift.domain.recommendation.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.exception.GlobalExceptionHandler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatConversationErrorResponseTest {

    @Test
    @DisplayName("대화 오류는 기존 공통 처리기로 HTTP 상태·코드·traceId를 반환한다")
    void handler_returnsCommonErrorEnvelope() throws Exception {
        var mvc = MockMvcBuilders
                .standaloneSetup(new ErrorController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        for (ErrorCode code : new ErrorCode[] {
                ErrorCode.NOT_FOUND,
                ErrorCode.UNAUTHORIZED,
                ErrorCode.RECIPIENT_NOT_FOUND,
                ErrorCode.INVALID_REQUEST
        }) {
            mvc.perform(
                            get("/test/chat-errors/{code}", code.name())
                    )
                    .andExpect(
                            status().is(code.status().value())
                    )
                    .andExpect(
                            jsonPath("$.message")
                                    .value(code.message())
                    )
                    .andExpect(
                            jsonPath("$.error.code")
                                    .value(code.name())
                    )
                    .andExpect(
                            jsonPath("$.error.traceId")
                                    .isNotEmpty()
                    )
                    .andExpect(
                            jsonPath("$.data")
                                    .doesNotExist()
                    )
                    .andExpect(
                            jsonPath("$.error.details")
                                    .doesNotExist()
                    );
        }
    }

    // 테스트 전용 Controller이며 운영 API로 등록되지 않는다.
    @RestController
    static class ErrorController {

        @GetMapping("/test/chat-errors/{code}")
        public void fail(@PathVariable String code) {
            throw new BusinessException(
                    ErrorCode.valueOf(code)
            );
        }
    }
}
