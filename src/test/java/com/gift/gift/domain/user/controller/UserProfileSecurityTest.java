package com.gift.gift.domain.user.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserProfileSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Access Token 없이 본인 정보를 조회하면 401을 반환한다")
    void getMyProfile_returnsUnauthorizedWithoutToken()
            throws Exception {
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code")
                        .value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Access Token 없이 회원정보를 수정하면 401을 반환한다")
    void updateMyProfile_returnsUnauthorizedWithoutToken()
            throws Exception {
        mockMvc.perform(
                        patch("/users/me")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "isBirthdayPublic": true
                                        }
                                        """)
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code")
                        .value("UNAUTHORIZED"));
    }
}
