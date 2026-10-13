package com.gift.gift.domain.recommendation.chat.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
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
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new ErrorController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        for (ChatErrorCode code : ChatErrorCode.values()) {
            mvc.perform(
                            get("/test/chat-errors/{code}", code.name())
                    )
                    .andExpect(
                            status().is(code.errorCode().status().value())
                    )
                    .andExpect(
                            jsonPath("$.message")
                                    .value(code.message())
                    )
                    .andExpect(
                            jsonPath("$.error.code")
                                    .value(code.errorCode().code())
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

    @Test
    @DisplayName("공통 충돌·장애 메시지는 채팅 문구를 포함하지 않는다")
    void commonDefaults_remainIndependentOfChat() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ErrorController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(get("/test/common-errors/CONFLICT"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("요청이 현재 상태와 충돌합니다."));

        mvc.perform(get("/test/common-errors/SERVICE_UNAVAILABLE"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(
                        "서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
                ));
    }

    // 테스트 전용 Controller이며 운영 API로 등록되지 않는다.
    @RestController
    static class ErrorController {

        @GetMapping("/test/common-errors/{code}")
        public void common(@PathVariable String code) {
            throw new BusinessException(ErrorCode.valueOf(code));
        }


        @GetMapping("/test/chat-errors/{code}")
        public void fail(@PathVariable String code) {
            throw new ChatException(ChatErrorCode.valueOf(code));
        }
    }
}
