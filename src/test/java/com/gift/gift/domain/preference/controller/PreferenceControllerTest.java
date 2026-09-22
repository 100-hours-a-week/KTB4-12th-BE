package com.gift.gift.domain.preference.controller;

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

import com.gift.gift.domain.preference.dto.response.DislikeCategoryItemResponse;
import com.gift.gift.domain.preference.dto.response.DislikeCategoryListResponse;
import com.gift.gift.domain.preference.exception.PreferenceException;
import com.gift.gift.domain.preference.service.PreferenceService;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.exception.GlobalExceptionHandler;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PreferenceControllerTest {

    private static final Long USER_ID = 1L;

    private PreferenceService preferenceService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        preferenceService =
                mock(PreferenceService.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new PreferenceController(
                                preferenceService
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
    @DisplayName("인증 사용자의 비선호 대분류와 선택 상태를 반환한다")
    void getDislikeCategories_returnsCategories() throws Exception {
        DislikeCategoryListResponse response =
                DislikeCategoryListResponse.from(List.of(
                        new DislikeCategoryItemResponse(
                                1L,
                                "뷰티",
                                true
                        ),
                        new DislikeCategoryItemResponse(
                                2L,
                                "식품",
                                false
                        )
                ));

        when(preferenceService.getDislikeCategories(USER_ID))
                .thenReturn(response);

        mockMvc.perform(
                        get("/preferences/dislike-categories")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("비선호 카테고리를 조회했습니다."))
                .andExpect(jsonPath("$.data.maxSelectableCount")
                        .value(5))
                .andExpect(jsonPath("$.data.categories.length()")
                        .value(2))
                .andExpect(jsonPath(
                        "$.data.categories[0].categoryId"
                ).value(1))
                .andExpect(jsonPath(
                        "$.data.categories[0].name"
                ).value("뷰티"))
                .andExpect(jsonPath(
                        "$.data.categories[0].isSelected"
                ).value(true))
                .andExpect(jsonPath(
                        "$.data.categories[1].categoryId"
                ).value(2))
                .andExpect(jsonPath(
                        "$.data.categories[1].name"
                ).value("식품"))
                .andExpect(jsonPath(
                        "$.data.categories[1].isSelected"
                ).value(false))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(preferenceService)
                .getDislikeCategories(USER_ID);
        verifyNoMoreInteractions(preferenceService);
    }

    @Test
    @DisplayName("선택 가능한 대분류가 없으면 빈 배열을 반환한다")
    void getDislikeCategories_returnsEmptyCategories() throws Exception {
        when(preferenceService.getDislikeCategories(USER_ID))
                .thenReturn(
                        DislikeCategoryListResponse.from(List.of())
                );

        mockMvc.perform(
                        get("/preferences/dislike-categories")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("비선호 카테고리를 조회했습니다."))
                .andExpect(jsonPath("$.data.maxSelectableCount")
                        .value(5))
                .andExpect(jsonPath("$.data.categories")
                        .isEmpty())
                .andExpect(jsonPath("$.error")
                        .doesNotExist());

        verify(preferenceService)
                .getDislikeCategories(USER_ID);
    }

    @Test
    @DisplayName("사용자를 찾을 수 없으면 404 USER_NOT_FOUND를 반환한다")
    void getDislikeCategories_returnsUserNotFound() throws Exception {
        when(preferenceService.getDislikeCategories(USER_ID))
                .thenThrow(new PreferenceException(
                        ErrorCode.USER_NOT_FOUND
                ));

        mockMvc.perform(
                        get("/preferences/dislike-categories")
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("사용자 정보를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.error.code")
                        .value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.error.traceId")
                        .isNotEmpty())
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist())
                .andExpect(jsonPath("$.data")
                        .doesNotExist());
    }

    @Test
    @DisplayName("예상하지 못한 오류는 500 공통 오류 응답을 반환한다")
    void getDislikeCategories_returnsInternalServerError()
            throws Exception {
        when(preferenceService.getDislikeCategories(USER_ID))
                .thenThrow(new IllegalStateException(
                        "unexpected error"
                ));

        mockMvc.perform(
                        get("/preferences/dislike-categories")
                )
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value("일시적인 오류가 발생했습니다. 다시 시도해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error.traceId")
                        .isNotEmpty())
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist())
                .andExpect(jsonPath("$.data")
                        .doesNotExist());
    }
}
