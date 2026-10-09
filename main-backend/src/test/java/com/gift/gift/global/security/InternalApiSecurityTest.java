package com.gift.gift.global.security;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.response.ApiResponse;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.ai-profile.service-token=test-internal-token")
@AutoConfigureMockMvc
@Import(InternalApiSecurityTest.SecurityProbeController.class)
@Transactional
class InternalApiSecurityTest {

    private static final String INTERNAL_PATH = "/api/internal/v1/test/auth";
    private static final String USER_PATH = "/test/security/user";
    private static final String SERVICE_TOKEN = "test-internal-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenProvider accessTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("내부 API는 올바른 서비스 토큰으로 접근할 수 있다")
    void internalApi_acceptsServiceToken() throws Exception {
        mockMvc.perform(post(INTERNAL_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isMap())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("내부 API는 토큰이 없으면 traceId가 있는 401을 반환한다")
    void internalApi_rejectsMissingToken() throws Exception {
        mockMvc.perform(post(INTERNAL_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("내부 API는 불일치하는 서비스 토큰을 거부한다")
    void internalApi_rejectsWrongToken() throws Exception {
        mockMvc.perform(post(INTERNAL_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("내부 API는 Bearer 방식이 아닌 인증 헤더를 거부한다")
    void internalApi_rejectsWrongScheme() throws Exception {
        mockMvc.perform(post(INTERNAL_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Basic " + SERVICE_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("내부 API는 비어 있는 Bearer 토큰을 거부한다")
    void internalApi_rejectsEmptyBearerToken() throws Exception {
        mockMvc.perform(post(INTERNAL_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer "))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("내부 API는 정상 발급된 사용자 JWT도 거부한다")
    void internalApi_rejectsUserJwt() throws Exception {
        String userToken = issuedUserToken();

        mockMvc.perform(post(INTERNAL_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("일반 보호 API는 서비스 토큰으로 접근할 수 없다")
    void userApi_rejectsServiceToken() throws Exception {
        mockMvc.perform(get(USER_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("일반 보호 API는 기존 사용자 JWT 인증을 유지한다")
    void userApi_acceptsUserJwt() throws Exception {
        String userToken = issuedUserToken();

        mockMvc.perform(get(USER_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("내부 API는 Authorization 헤더가 여러 개면 거부한다")
    void internalApi_rejectsMultipleAuthorizationHeaders() throws Exception {
        mockMvc.perform(post(INTERNAL_PATH)
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + SERVICE_TOKEN,
                                "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    private String issuedUserToken() {
        User user = userRepository.saveAndFlush(new User(
                UUID.randomUUID() + "@example.com",
                "$2a$10$" + "a".repeat(53),
                "인증검증사용자",
                LocalDate.of(2000, 1, 1)
        ));

        return accessTokenProvider.issue(user.getId()).value();
    }

    @RestController
    public static class SecurityProbeController {

        @PostMapping(INTERNAL_PATH)
        public ApiResponse<?> internalApi() {
            return ApiResponse.success("내부 인증을 확인했습니다.");
        }

        @GetMapping(USER_PATH)
        public ApiResponse<?> userApi() {
            return ApiResponse.success("사용자 인증을 확인했습니다.");
        }
    }
}
