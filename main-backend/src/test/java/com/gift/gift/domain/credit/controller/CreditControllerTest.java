package com.gift.gift.domain.credit.controller;

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

import com.gift.gift.domain.credit.dto.response.CreditBalanceResponse;
import com.gift.gift.domain.credit.service.CreditQueryService;
import com.gift.gift.global.exception.GlobalExceptionHandler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CreditControllerTest {

    private static final Long USER_ID = 1L;

    private CreditQueryService creditQueryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        creditQueryService = mock(CreditQueryService.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new CreditController(creditQueryService))
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
                new JwtAuthenticationToken(
                        jwt,
                        List.of(),
                        USER_ID.toString()
                )
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("현재 크레딧 잔액을 200 응답으로 반환한다")
    void getBalance_returnsCurrentBalance() throws Exception {
        when(creditQueryService.getBalance(USER_ID))
                .thenReturn(
                        new CreditBalanceResponse(436_000L)
                );

        mockMvc.perform(get("/credits/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("크레딧 잔액을 조회했습니다."))
                .andExpect(jsonPath("$.data.balance").value(436_000L))
                .andExpect(jsonPath("$.data.updatedAt").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(creditQueryService).getBalance(USER_ID);
        verifyNoMoreInteractions(creditQueryService);
    }

    @Test
    @DisplayName("잔액 조회 중 예상하지 못한 오류가 발생하면 500을 반환한다")
    void getBalance_returnsInternalServerError() throws Exception {
        when(creditQueryService.getBalance(USER_ID))
                .thenThrow(new IllegalStateException(
                        "사용자의 크레딧 계좌가 존재하지 않습니다."
                ));

        mockMvc.perform(get("/credits/balance"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("일시적인 오류가 발생했습니다. 다시 시도해 주세요."))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.error.details").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(creditQueryService).getBalance(USER_ID);
        verifyNoMoreInteractions(creditQueryService);
    }
}
