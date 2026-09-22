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

import com.gift.gift.domain.user.dto.response.SignupResponse;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.service.SignupService;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.exception.GlobalExceptionHandler;
import com.gift.gift.support.TestValidatorFactory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SignupControllerTest {

    private SignupService service;
    private MockMvc mockMvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        service = mock(SignupService.class);
        validator = TestValidatorFactory.create();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new SignupController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    @DisplayName("가입 성공 시 201과 회원 ID만 반환한다")
    void signup_returnsCreatedResponse() throws Exception {
        User user = new User(
                "user@example.com", "hash", "김선물", LocalDate.of(2000, 1, 1)
        );
        ReflectionTestUtils.setField(user, "id", 10L);

        when(service.signup(any())).thenReturn(SignupResponse.from(user));

        mockMvc.perform(post("/auth/signup")
                        .contentType(APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userId").value(10))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("DTO 검증 오류는 INVALID_REQUEST와 details를 반환한다")
    void signup_returnsDetailsForDtoValidationError() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(APPLICATION_JSON)
                        .content(body().replace("김선물", "김선물1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.error.details[0].field").value("name"))
                .andExpect(jsonPath("$.error.details[0].reason").value("INVALID_FORMAT"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("이름 형식과 만 14세 정책 오류를 details에 함께 반환한다")
    void signup_returnsAllDtoValidationErrors() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(APPLICATION_JSON)
                        .content(body()
                                .replace("김선물", "김선물1")
                                .replace("2000-01-01", "2012-09-19")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.error.details[0].field").value("birth"))
                .andExpect(jsonPath("$.error.details[0].reason")
                        .value("AGE_REQUIREMENT_NOT_MET"))
                .andExpect(jsonPath("$.error.details[1].field").value("name"))
                .andExpect(jsonPath("$.error.details[1].reason")
                        .value("INVALID_FORMAT"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("이메일 중복은 details 없는 409를 반환한다")
    void signup_returnsConflictForDuplicateEmail() throws Exception {
        when(service.signup(any())).thenThrow(
                new BusinessException(ErrorCode.EMAIL_ALREADY_IN_USE)
        );

        mockMvc.perform(post("/auth/signup")
                        .contentType(APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_IN_USE"))
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }

    @Test
    @DisplayName("약관 버전 오류는 details 없는 전용 코드를 반환한다")
    void signup_returnsTermVersionError() throws Exception {
        when(service.signup(any())).thenThrow(
                new BusinessException(ErrorCode.INVALID_TERM_VERSION)
        );

        mockMvc.perform(post("/auth/signup")
                        .contentType(APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_TERM_VERSION"))
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }

    @Test
    @DisplayName("필수 약관 미동의는 details 없는 전용 코드를 반환한다")
    void signup_returnsRequiredTermsError() throws Exception {
        when(service.signup(any())).thenThrow(
                new BusinessException(ErrorCode.REQUIRED_TERMS_NOT_AGREED)
        );

        mockMvc.perform(post("/auth/signup")
                        .contentType(APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUIRED_TERMS_NOT_AGREED"))
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }

    @Test
    @DisplayName("잘못된 JSON은 details 없는 요청 오류를 반환한다")
    void signup_returnsInvalidRequestForMalformedJson() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details").doesNotExist());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("내부 오류 메시지를 서버 오류 응답으로 노출하지 않는다")
    void signup_hidesInternalErrorMessage() throws Exception {
        when(service.signup(any())).thenThrow(
                new IllegalStateException("internal-only-message")
        );

        mockMvc.perform(post("/auth/signup")
                        .contentType(APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.INTERNAL_SERVER_ERROR.message()))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private String body() {
        return """
                {
                  "name": "김선물",
                  "birth": "2000-01-01",
                  "email": "user@example.com",
                  "password": "Password1!",
                  "termConsents": [
                    {"termId": 1, "version": 3, "isAgreed": true}
                  ]
                }
                """;
    }
}
