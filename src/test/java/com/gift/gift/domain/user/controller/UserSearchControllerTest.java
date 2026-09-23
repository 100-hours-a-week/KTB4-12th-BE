package com.gift.gift.domain.user.controller;

import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.service.UserSearchService;
import com.gift.gift.global.exception.GlobalExceptionHandler;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserSearchControllerTest {

    private UserSearchService userSearchService;
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userSearchService = mock(UserSearchService.class);

        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new UserSearchController(userSearchService)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    @DisplayName("이메일과 일치하는 회원이 있으면 200과 회원 정보를 반환한다")
    void search_returnsMatchedUser() throws Exception {
        User user = user(
                27L,
                "user@example.com",
                "김민정"
        );

        when(userSearchService.search("user@example.com"))
                .thenReturn(UserSearchResponse.from(user));

        mockMvc.perform(
                        get("/users/search")
                                .param(
                                        "email",
                                        "  User@Example.com  "
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("친구 추가 대상 회원을 조회했습니다."))
                .andExpect(jsonPath("$.data.user.userId")
                        .value(27))
                .andExpect(jsonPath("$.data.user.name")
                        .value("김민정"))
                .andExpect(jsonPath("$.data.user.email")
                        .value("user@example.com"))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(userSearchService)
                .search("user@example.com");
    }

    @Test
    @DisplayName("일치하는 회원이 없으면 200과 user null을 반환한다")
    void search_returnsNullUserWhenNoUserMatches()
            throws Exception {

        when(userSearchService.search("missing@example.com"))
                .thenReturn(UserSearchResponse.notFound());

        mockMvc.perform(
                        get("/users/search")
                                .param(
                                        "email",
                                        "missing@example.com"
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("일치하는 사용자가 없습니다."))
                .andExpect(jsonPath("$.data.user")
                        .value(nullValue()))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("이메일 형식이 잘못되면 details 없는 400을 반환한다")
    void search_rejectsInvalidEmail() throws Exception {
        mockMvc.perform(
                        get("/users/search")
                                .param("email", "invalid-email")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("이메일 형식을 확인해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId")
                        .isNotEmpty())
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist())
                .andExpect(jsonPath("$.data")
                        .doesNotExist());

        verifyNoInteractions(userSearchService);
    }

    @Test
    @DisplayName("email이 없으면 details 없는 400을 반환한다")
    void search_rejectsMissingEmail() throws Exception {
        mockMvc.perform(get("/users/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist());

        verifyNoInteractions(userSearchService);
    }

    @Test
    @DisplayName("허용되지 않은 Query Parameter가 있으면 400을 반환한다")
    void search_rejectsUnexpectedQueryParameter()
            throws Exception {

        mockMvc.perform(
                        get("/users/search")
                                .param(
                                        "email",
                                        "user@example.com"
                                )
                                .param("page", "1")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist());

        verifyNoInteractions(userSearchService);
    }

    private User user(
            Long id,
            String email,
            String name
    ) {
        User user = new User(
                email,
                "$2a$10$12345678901234567890123456789012345678901234567890123",
                name,
                LocalDate.of(1995, 1, 1)
        );

        ReflectionTestUtils.setField(user, "id", id);

        return user;
    }
}
