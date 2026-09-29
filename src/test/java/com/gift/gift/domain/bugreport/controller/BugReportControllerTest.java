package com.gift.gift.domain.bugreport.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.gift.gift.domain.bugreport.exception.BugReportErrorCode;
import com.gift.gift.domain.bugreport.exception.BugReportException;
import com.gift.gift.domain.bugreport.service.BugReportService;
import com.gift.gift.global.exception.GlobalExceptionHandler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BugReportControllerTest {

    private static final Long USER_ID = 1L;
    private static final String VALID_PAYLOAD =
            "{\"embeds\":[{\"description\":\"버그 설명\"}]}";

    private BugReportService bugReportService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        bugReportService = mock(BugReportService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BugReportController(bugReportService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        Jwt jwt = Jwt.withTokenValue("access-token")
                .header("alg", "HS256")
                .subject(USER_ID.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .issuer("https://test-issuer.example")
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(), USER_ID.toString())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("스크린샷과 로그가 모두 있으면 204를 반환하고 서비스에 그대로 전달한다")
    void submitBugReport_returns204_withAllAttachments() throws Exception {
        mockMvc.perform(multipart("/bug-report")
                        .file("payload_json", VALID_PAYLOAD.getBytes(
                                java.nio.charset.StandardCharsets.UTF_8
                        ))
                        .file("files[0]", "png-bytes".getBytes())
                        .file("files[1]", "log".getBytes()))
                .andExpect(status().isNoContent());

        verify(bugReportService).submitBugReport(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(VALID_PAYLOAD),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("인증 토큰이 없어도 204를 반환하고 사용자 ID 없이 서비스에 전달한다")
    void submitBugReport_returns204_forAnonymousRequest() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(multipart("/bug-report")
                        .file("payload_json", VALID_PAYLOAD.getBytes(
                                java.nio.charset.StandardCharsets.UTF_8
                        )))
                .andExpect(status().isNoContent());

        verify(bugReportService).submitBugReport(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(VALID_PAYLOAD),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("파일이 없어도 204를 반환한다")
    void submitBugReport_returns204_withoutFiles() throws Exception {
        mockMvc.perform(multipart("/bug-report")
                        .file("payload_json", VALID_PAYLOAD.getBytes(
                                java.nio.charset.StandardCharsets.UTF_8
                        )))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("검증 실패 시 서비스 예외를 그대로 400 응답으로 변환한다")
    void submitBugReport_returns400_onValidationFailure() throws Exception {
        doThrow(new BugReportException(BugReportErrorCode.DESCRIPTION_REQUIRED))
                .when(bugReportService)
                .submitBugReport(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()
                );

        mockMvc.perform(multipart("/bug-report")
                        .file("payload_json", VALID_PAYLOAD.getBytes(
                                java.nio.charset.StandardCharsets.UTF_8
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("버그 설명을 입력해 주세요."));
    }

    @Test
    @DisplayName("rate limit 초과 시 429 응답을 반환한다")
    void submitBugReport_returns429_onRateLimitExceeded() throws Exception {
        doThrow(new BugReportException(BugReportErrorCode.RATE_LIMIT_EXCEEDED))
                .when(bugReportService)
                .submitBugReport(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()
                );

        mockMvc.perform(multipart("/bug-report")
                        .file("payload_json", VALID_PAYLOAD.getBytes(
                                java.nio.charset.StandardCharsets.UTF_8
                        )))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Discord 전송 실패 시 502 응답을 반환한다")
    void submitBugReport_returns502_onDiscordDeliveryFailure() throws Exception {
        doThrow(new BugReportException(
                        BugReportErrorCode.DELIVERY_FAILED,
                        "Discord 전송 실패 (HTTP 500)"
                ))
                .when(bugReportService)
                .submitBugReport(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()
                );

        mockMvc.perform(multipart("/bug-report")
                        .file("payload_json", VALID_PAYLOAD.getBytes(
                                java.nio.charset.StandardCharsets.UTF_8
                        )))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Discord 전송 실패 (HTTP 500)"));
    }
}
