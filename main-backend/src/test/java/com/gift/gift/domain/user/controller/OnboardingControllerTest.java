package com.gift.gift.domain.user.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.gift.gift.domain.user.dto.response.CompleteOnboardingResponse;
import com.gift.gift.domain.user.service.OnboardingService;
import com.gift.gift.domain.user.service.UserProfileService;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.exception.GlobalExceptionHandler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OnboardingControllerTest {

    private static final Long USER_ID = 1L;

    private OnboardingService onboardingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserProfileService userProfileService =
                mock(UserProfileService.class);
        onboardingService = mock(OnboardingService.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new UserProfileController(
                                userProfileService,
                                onboardingService
                        )
                )
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver()
                )
                .setControllerAdvice(
                        new GlobalExceptionHandler()
                )
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
    @DisplayName("정상 온보딩 완료 요청은 200과 false 상태를 반환한다")
    void completeOnboarding_returnsSuccess() throws Exception {
        when(onboardingService.completeOnboarding(USER_ID))
                .thenReturn(
                        new CompleteOnboardingResponse(false)
                );

        mockMvc.perform(
                        patch("/users/me/onboarding")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "completed": true
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("최초 로그인 설정을 완료했습니다."))
                .andExpect(jsonPath("$.data.isFirstLogin")
                        .value(false))
                .andExpect(jsonPath("$.error")
                        .doesNotExist());

        verify(onboardingService)
                .completeOnboarding(USER_ID);
    }

    @Test
    @DisplayName("completed 누락은 REQUIRED details와 400을 반환한다")
    void completeOnboarding_rejectsMissingCompleted()
            throws Exception {
        mockMvc.perform(
                        patch("/users/me/onboarding")
                                .contentType(APPLICATION_JSON)
                                .content("{}")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("입력값을 확인해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath(
                        "$.error.details[0].field"
                ).value("completed"))
                .andExpect(jsonPath(
                        "$.error.details[0].reason"
                ).value("REQUIRED"));

        verifyNoInteractions(onboardingService);
    }

    @Test
    @DisplayName("completed null은 REQUIRED details와 400을 반환한다")
    void completeOnboarding_rejectsNullCompleted()
            throws Exception {
        mockMvc.perform(
                        patch("/users/me/onboarding")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "completed": null
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.error.details[0].field"
                ).value("completed"))
                .andExpect(jsonPath(
                        "$.error.details[0].reason"
                ).value("REQUIRED"));

        verifyNoInteractions(onboardingService);
    }

    @Test
    @DisplayName("Boolean이 아닌 completed는 INVALID_FORMAT으로 거부한다")
    void completeOnboarding_rejectsInvalidFormat()
            throws Exception {
        mockMvc.perform(
                        patch("/users/me/onboarding")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "completed": "true"
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.error.details[0].field"
                ).value("completed"))
                .andExpect(jsonPath(
                        "$.error.details[0].reason"
                ).value("INVALID_FORMAT"));

        verifyNoInteractions(onboardingService);
    }

    @Test
    @DisplayName("completed false는 INVALID_VALUE로 거부한다")
    void completeOnboarding_rejectsFalse()
            throws Exception {
        mockMvc.perform(
                        patch("/users/me/onboarding")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "completed": false
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.error.details[0].field"
                ).value("completed"))
                .andExpect(jsonPath(
                        "$.error.details[0].reason"
                ).value("INVALID_VALUE"));

        verifyNoInteractions(onboardingService);
    }

    @Test
    @DisplayName("추가 필드는 details 없이 400으로 거부한다")
    void completeOnboarding_rejectsUnknownFieldWithoutDetails()
            throws Exception {
        mockMvc.perform(
                        patch("/users/me/onboarding")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "completed": true,
                                          "name": "변경 이름"
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist());

        verifyNoInteractions(onboardingService);
    }

    @Test
    @DisplayName("잘못된 JSON은 details 없이 400으로 거부한다")
    void completeOnboarding_rejectsMalformedJsonWithoutDetails()
            throws Exception {
        mockMvc.perform(
                        patch("/users/me/onboarding")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "completed":
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist());

        verifyNoInteractions(onboardingService);
    }

    @Test
    @DisplayName("활성 회원이 없으면 404를 반환한다")
    void completeOnboarding_returnsUserNotFound()
            throws Exception {
        when(onboardingService.completeOnboarding(USER_ID))
                .thenThrow(new BusinessException(
                        ErrorCode.USER_NOT_FOUND
                ));

        mockMvc.perform(
                        patch("/users/me/onboarding")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "completed": true
                                        }
                                        """)
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code")
                        .value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist());
    }

    @Test
    @DisplayName("저장 실패는 공통 500을 반환한다")
    void completeOnboarding_returnsInternalServerError()
            throws Exception {
        when(onboardingService.completeOnboarding(USER_ID))
                .thenThrow(
                        new DataAccessResourceFailureException(
                                "user storage unavailable"
                        )
                );

        mockMvc.perform(
                        patch("/users/me/onboarding")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "completed": true
                                        }
                                        """)
                )
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.INTERNAL_SERVER_ERROR.message()))
                .andExpect(jsonPath("$.error.code")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist());
    }
}
