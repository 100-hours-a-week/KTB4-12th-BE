package com.gift.gift.domain.user.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SignupSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("가입은 인증과 CSRF 토큰 없이 DTO 검증 단계에 도달한다")
    void signup_allowsAnonymousRequestWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("이메일 확인도 인증과 CSRF 토큰 없이 검증 단계에 도달한다")
    void emailAvailability_allowsAnonymousRequestWithoutCsrfToken()
            throws Exception {
        mockMvc.perform(post("/auth/email-availability")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_EMAIL_FORMAT"));
    }

    @Test
    @DisplayName("보호 POST는 토큰이 없으면 401 공통 응답을 반환한다")
    void protectedPost_rejectsMissingAccessToken() throws Exception {
        mockMvc.perform(post("/users/me")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }
}
