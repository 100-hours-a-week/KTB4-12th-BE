package com.gift.gift.domain.user.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gift.gift.domain.user.exception.LoginRateLimitExceededException;
import com.gift.gift.domain.user.service.LoginRateLimiter;
import com.gift.gift.domain.user.service.LoginService;
import com.gift.gift.domain.auth.support.LoginResult;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LoginControllerTest {

    private static final String REFRESH_TOKEN = "r".repeat(43);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoginService loginService;

    @MockitoBean
    private LoginRateLimiter loginRateLimiter;

    @Test
    @DisplayName("Access Token 없이 로그인하면 200과 Access Token 응답을 반환한다")
    void login_allowsAnonymousRequestAndReturnsSuccess() throws Exception {
        when(loginService.login(any(), isNull()))
                .thenReturn(successResult());

        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("로그인에 성공했습니다."))
                .andExpect(jsonPath("$.data.accessToken")
                        .value("access-token"))
                .andExpect(jsonPath("$.data.expiresIn").value(3600))
                .andExpect(jsonPath("$.data.user.userId").value(1))
                .andExpect(jsonPath("$.data.user.email")
                        .value("user@example.com"))
                .andExpect(jsonPath("$.data.isFirstLogin").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("로그인 성공 Cookie는 HttpOnly Secure SameSite Lax와 auth 경로를 사용한다")
    void login_setsSecureHttpOnlyRefreshCookie() throws Exception {
        when(loginService.login(any(), isNull()))
                .thenReturn(successResult());

        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString(
                                        "refreshToken=" + REFRESH_TOKEN
                                ),
                                org.hamcrest.Matchers.containsString("HttpOnly"),
                                org.hamcrest.Matchers.containsString("Secure"),
                                org.hamcrest.Matchers.containsString("SameSite=Lax"),
                                org.hamcrest.Matchers.containsString("Path=/auth")
                        )
                ));
    }

    @Test
    @DisplayName("Refresh Token은 JSON 응답에 포함하지 않는다")
    void login_doesNotExposeRefreshTokenInJson() throws Exception {
        when(loginService.login(any(), isNull()))
                .thenReturn(successResult());

        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    @DisplayName("잘못된 이메일 형식도 IP 토큰 차감 후 400과 details를 반환한다")
    void login_returnsValidationDetailsForInvalidEmailAfterIpCheck()
            throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "invalid-email",
                                  "password": "Password1!"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details[0].field")
                        .value("email"))
                .andExpect(jsonPath("$.error.details[0].reason")
                        .value("INVALID_FORMAT"));

        verify(loginRateLimiter).checkIpAttempt(any());
        verify(loginService, never()).login(any(), any());
    }

    @Test
    @DisplayName("비밀번호 누락도 IP 토큰 차감 후 400과 details를 반환한다")
    void login_returnsValidationDetailsForMissingPasswordAfterIpCheck()
            throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "user@example.com"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details[0].field")
                        .value("password"))
                .andExpect(jsonPath("$.error.details[0].reason")
                        .value("REQUIRED"));

        verify(loginRateLimiter).checkIpAttempt(any());
        verify(loginService, never()).login(any(), any());
    }

    @Test
    @DisplayName("가입되지 않은 이메일은 자격 증명 노출 없이 401을 반환한다")
    void login_returnsUnauthorizedForUnknownEmail() throws Exception {
        when(loginService.login(any(), isNull()))
                .thenThrow(new BusinessException(
                        ErrorCode.INVALID_CREDENTIALS
                ));

        assertInvalidCredentialsResponse();
    }

    @Test
    @DisplayName("비밀번호 불일치는 이메일 미등록과 동일한 401을 반환한다")
    void login_returnsUnauthorizedForPasswordMismatch() throws Exception {
        when(loginService.login(any(), isNull()))
                .thenThrow(new BusinessException(
                        ErrorCode.INVALID_CREDENTIALS
                ));

        assertInvalidCredentialsResponse();
    }

    @Test
    @DisplayName("IP 요청 제한은 DTO 검증보다 먼저 429와 Retry-After를 반환한다")
    void login_returnsRateLimitBeforeDtoValidation() throws Exception {
        doThrow(new LoginRateLimitExceededException(6))
                .when(loginRateLimiter)
                .checkIpAttempt(any());

        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "6"))
                .andExpect(jsonPath("$.error.code")
                        .value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.error.details").doesNotExist());

        verify(loginService, never()).login(any(), any());
    }

    @Test
    @DisplayName("세션 저장 실패는 500을 반환하고 Refresh Cookie를 발급하지 않는다")
    void login_doesNotSetCookieWhenSessionPersistenceFails()
            throws Exception {
        when(loginService.login(any(), isNull()))
                .thenThrow(new DataAccessResourceFailureException(
                        "session storage unavailable"
                ));

        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.error.code")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.INTERNAL_SERVER_ERROR.message()));
    }

    private void assertInvalidCredentialsResponse() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.INVALID_CREDENTIALS.message()))
                .andExpect(jsonPath("$.error.details").doesNotExist())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    private LoginResult successResult() {
        return new LoginResult(
                "access-token",
                3600,
                REFRESH_TOKEN,
                1L,
                "김선물",
                "user@example.com",
                true
        );
    }

    private String validBody() {
        return """
                {
                  "email": "user@example.com",
                  "password": "Password1!"
                }
                """;
    }
}
