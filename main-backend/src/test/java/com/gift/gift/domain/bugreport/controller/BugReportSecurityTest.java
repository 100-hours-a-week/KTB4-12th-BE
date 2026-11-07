package com.gift.gift.domain.bugreport.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class BugReportSecurityTest {
    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("익명 JSON 요청도 인증 오류가 아닌 필드 검증에 도달한다")
    void allowsAnonymousRequest() throws Exception {
        mockMvc.perform(post("/bug-report").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
