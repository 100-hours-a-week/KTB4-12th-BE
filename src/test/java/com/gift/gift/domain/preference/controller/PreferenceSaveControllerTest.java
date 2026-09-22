package com.gift.gift.domain.preference.controller;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.gift.gift.domain.preference.dto.response.SaveDislikeCategoriesResponse;
import com.gift.gift.domain.preference.exception.PreferenceException;
import com.gift.gift.domain.preference.service.PreferenceService;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.exception.GlobalExceptionHandler;
import com.gift.gift.support.TestValidatorFactory;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PreferenceSaveControllerTest {

    private static final Long USER_ID = 1L;

    private PreferenceService preferenceService;
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        preferenceService = mock(PreferenceService.class);
        validator = TestValidatorFactory.create();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new PreferenceController(preferenceService))
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver()
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
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
        validator.close();
    }

    @Test
    @DisplayName("선택한 비선호 카테고리 ID를 저장 결과로 반환한다")
    void saveDislikeCategories_returnsSelectedCategoryIds()
            throws Exception {
        when(preferenceService.saveDislikeCategories(
                USER_ID,
                List.of(1L, 3L)
        )).thenReturn(SaveDislikeCategoriesResponse.from(
                List.of(1L, 3L)
        ));

        mockMvc.perform(put("/preferences/dislike-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryIds": [1, 3]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("비선호 카테고리를 저장했습니다."))
                .andExpect(jsonPath("$.data.selectedCategoryIds[0]")
                        .value(1))
                .andExpect(jsonPath("$.data.selectedCategoryIds[1]")
                        .value(3))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(preferenceService).saveDislikeCategories(
                USER_ID,
                List.of(1L, 3L)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("잘못된 categoryIds는 필드별 400 오류를 반환한다")
    void saveDislikeCategories_returnsValidationDetails(
            String description,
            String body,
            String field,
            String reason
    ) throws Exception {
        mockMvc.perform(put("/preferences/dislike-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId")
                        .isNotEmpty())
                .andExpect(jsonPath("$.error.details[0].field")
                        .value(field))
                .andExpect(jsonPath("$.error.details[0].reason")
                        .value(reason))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(preferenceService);
    }

    @Test
    @DisplayName("배열이 아닌 categoryIds는 details 없는 400 오류를 반환한다")
    void saveDislikeCategories_rejectsInvalidJsonType()
            throws Exception {
        mockMvc.perform(put("/preferences/dislike-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryIds": "1, 2"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist());

        verifyNoInteractions(preferenceService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("businessErrors")
    @DisplayName("저장 비즈니스 오류는 명세의 상태와 코드를 반환한다")
    void saveDislikeCategories_returnsBusinessError(
            String description,
            ErrorCode errorCode
    ) throws Exception {
        when(preferenceService.saveDislikeCategories(
                USER_ID,
                List.of(1L)
        )).thenThrow(new PreferenceException(errorCode));

        mockMvc.perform(put("/preferences/dislike-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryIds": [1]
                                }
                                """))
                .andExpect(status().is(errorCode.status().value()))
                .andExpect(jsonPath("$.message")
                        .value(errorCode.message()))
                .andExpect(jsonPath("$.error.code")
                        .value(errorCode.code()))
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist());
    }

    private static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of(
                        "필드 누락",
                        "{}",
                        "categoryIds",
                        "REQUIRED"
                ),
                Arguments.of(
                        "중복 ID",
                        "{\"categoryIds\":[1,1]}",
                        "categoryIds",
                        "DUPLICATE_CATEGORY_ID"
                ),
                Arguments.of(
                        "양수가 아닌 ID",
                        "{\"categoryIds\":[0]}",
                        "categoryIds[0]",
                        "OUT_OF_RANGE"
                )
        );
    }

    private static Stream<Arguments> businessErrors() {
        return Stream.of(
                Arguments.of(
                        "사용자 없음",
                        ErrorCode.USER_NOT_FOUND
                ),
                Arguments.of(
                        "선택 개수 초과",
                        ErrorCode.TOO_MANY_DISLIKE_CATEGORIES
                ),
                Arguments.of(
                        "선택 불가 카테고리",
                        ErrorCode.DISLIKE_CATEGORY_NOT_AVAILABLE
                )
        );
    }
}
