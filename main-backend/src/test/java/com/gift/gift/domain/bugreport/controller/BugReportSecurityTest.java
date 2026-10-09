package com.gift.gift.domain.bugreport.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BugReportSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("버그 제보는 인증 토큰 없이도 401이 아닌 검증 단계에 도달한다")
    void bugReport_allowsAnonymousRequest_reachesValidation() throws Exception {
        mockMvc.perform(multipart("/bug-report"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
