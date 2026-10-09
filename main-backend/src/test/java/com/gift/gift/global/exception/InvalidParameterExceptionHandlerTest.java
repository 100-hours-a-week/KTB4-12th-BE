package com.gift.gift.global.exception;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InvalidParameterExceptionHandlerTest {

    @Test
    @DisplayName("파라미터 디코딩 실패는 500 대신 400과 공통 오류 응답을 반환한다")
    void decodingFailureReturnsBadRequest() throws Exception {
        MockMvcBuilders.standaloneSetup(new InvalidParameterController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build()
                .perform(get("/parameter-check"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @RestController
    static class InvalidParameterController {
        @GetMapping("/parameter-check")
        String getParameter() {
            throw new InvalidParameterException("Character decoding failed");
        }
    }
}
