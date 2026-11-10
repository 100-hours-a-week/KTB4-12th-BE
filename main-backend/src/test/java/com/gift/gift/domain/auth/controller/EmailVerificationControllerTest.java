package com.gift.gift.domain.auth.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gift.gift.domain.auth.exception.AuthErrorCode;
import com.gift.gift.domain.auth.exception.AuthException;
import com.gift.gift.domain.auth.service.EmailVerificationService;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EmailVerificationControllerTest {

    private static final String EMAIL = "user@example.com";
    private static final String CLIENT_IP = "203.0.113.7";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @Test
    @DisplayName("인증번호 발급 성공은 200과 빈 data를 반환하고 이메일과 IP를 Service에 전달한다")
    void sendVerificationCode_returnsSuccessAndPassesEmailAndIp()
            throws Exception {
        mockMvc.perform(post("/auth/email-verification-codes")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": " USER@example.com "
                                }
                                """)
                        .with(request -> {
                            request.setRemoteAddr(CLIENT_IP);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("이메일 인증번호를 전송했습니다."))
                .andExpect(content().json("""
                        {
                          "data": {}
                        }
                        """, false))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(emailVerificationService)
                .sendVerificationCode(EMAIL, CLIENT_IP);
    }

    @Test
    @DisplayName("인증번호 검증 성공은 200과 가입용 토큰을 반환한다")
    void verifyCode_returnsSignupToken() throws Exception {
        when(emailVerificationService.verifyCode(EMAIL, "123456"))
                .thenReturn("signup-token");

        mockMvc.perform(post("/auth/email-verifications")
                        .contentType(APPLICATION_JSON)
                        .content(verifyBody("123456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("이메일 인증이 완료되었습니다."))
                .andExpect(jsonPath("$.data.emailVerificationToken")
                        .value("signup-token"))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(emailVerificationService).verifyCode(EMAIL, "123456");
    }

    @Test
    @DisplayName("이메일 형식이 잘못되면 Service를 호출하지 않고 400과 필드 details를 반환한다")
    void sendVerificationCode_returnsValidationDetailsForInvalidEmail()
            throws Exception {
        mockMvc.perform(post("/auth/email-verification-codes")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "not-an-email"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId")
                        .value(org.hamcrest.Matchers.matchesPattern(
                                "^[0-9a-f]{32}$"
                        )))
                .andExpect(jsonPath("$.error.details[0].field")
                        .value("email"))
                .andExpect(jsonPath("$.error.details[0].reason")
                        .value("INVALID_FORMAT"));

        verify(emailVerificationService, never())
                .sendVerificationCode(anyString(), anyString());
    }

    @Test
    @DisplayName("인증번호 형식이 잘못되면 Service를 호출하지 않고 400과 필드 details를 반환한다")
    void verifyCode_returnsValidationDetailsForInvalidCode()
            throws Exception {
        mockMvc.perform(post("/auth/email-verifications")
                        .contentType(APPLICATION_JSON)
                        .content(verifyBody("12x")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId")
                        .value(org.hamcrest.Matchers.matchesPattern(
                                "^[0-9a-f]{32}$"
                        )))
                .andExpect(jsonPath("$.error.details[0].field")
                        .value("code"))
                .andExpect(jsonPath("$.error.details[0].reason")
                        .value("INVALID_FORMAT"));

        verify(emailVerificationService, never())
                .verifyCode(anyString(), anyString());
    }

    @Test
    @DisplayName("발급 제한 초과는 429와 공통 오류 응답을 반환한다")
    void sendVerificationCode_returnsTooManyRequestsWhenLimited()
            throws Exception {
        doThrow(new AuthException(
                AuthErrorCode.EMAIL_VERIFICATION_RATE_LIMIT_EXCEEDED
        )).when(emailVerificationService)
                .sendVerificationCode(EMAIL, CLIENT_IP);

        mockMvc.perform(post("/auth/email-verification-codes")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "user@example.com"
                                }
                                """)
                        .with(request -> {
                            request.setRemoteAddr(CLIENT_IP);
                            return request;
                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code")
                        .value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.error.traceId")
                        .value(org.hamcrest.Matchers.matchesPattern(
                                "^[0-9a-f]{32}$"
                        )))
                .andExpect(jsonPath("$.error.details").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("인증번호 불일치는 400과 공통 오류 응답을 반환한다")
    void verifyCode_returnsBadRequestWhenCodeDoesNotMatch()
            throws Exception {
        when(emailVerificationService.verifyCode(EMAIL, "123456"))
                .thenThrow(new AuthException(
                        AuthErrorCode.EMAIL_VERIFICATION_CODE_INVALID
                ));

        mockMvc.perform(post("/auth/email-verifications")
                        .contentType(APPLICATION_JSON)
                        .content(verifyBody("123456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("인증번호가 올바르지 않거나 만료되었습니다."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId")
                        .value(org.hamcrest.Matchers.matchesPattern(
                                "^[0-9a-f]{32}$"
                        )))
                .andExpect(jsonPath("$.error.details").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("Redis 또는 SMTP 장애는 인증번호 발급에서 503과 공통 오류 응답을 반환한다")
    void sendVerificationCode_returnsServiceUnavailableOnDependencyFailure()
            throws Exception {
        doThrow(new AuthException(
                AuthErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE
        )).when(emailVerificationService)
                .sendVerificationCode(EMAIL, CLIENT_IP);

        mockMvc.perform(post("/auth/email-verification-codes")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "user@example.com"
                                }
                                """)
                        .with(request -> {
                            request.setRemoteAddr(CLIENT_IP);
                            return request;
                        }))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code")
                        .value("AUTHENTICATION_TEMPORARILY_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.traceId")
                        .value(org.hamcrest.Matchers.matchesPattern(
                                "^[0-9a-f]{32}$"
                        )))
                .andExpect(jsonPath("$.error.details").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("Redis 장애는 인증번호 검증에서 503과 공통 오류 응답을 반환한다")
    void verifyCode_returnsServiceUnavailableOnRedisFailure()
            throws Exception {
        when(emailVerificationService.verifyCode(EMAIL, "123456"))
                .thenThrow(new AuthException(
                        AuthErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE
                ));

        mockMvc.perform(post("/auth/email-verifications")
                        .contentType(APPLICATION_JSON)
                        .content(verifyBody("123456")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code")
                        .value("AUTHENTICATION_TEMPORARILY_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.traceId")
                        .value(org.hamcrest.Matchers.matchesPattern(
                                "^[0-9a-f]{32}$"
                        )))
                .andExpect(jsonPath("$.error.details").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private String verifyBody(String code) {
        return """
                {
                  "email": "user@example.com",
                  "code": "%s"
                }
                """.formatted(code);
    }
}
