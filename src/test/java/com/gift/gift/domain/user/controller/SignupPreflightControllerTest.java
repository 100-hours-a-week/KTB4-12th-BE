package com.gift.gift.domain.user.controller;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.gift.gift.domain.user.dto.response.EmailAvailabilityResponse;
import com.gift.gift.domain.user.dto.response.SignupTermsResponse;
import com.gift.gift.domain.user.entity.Term;
import com.gift.gift.domain.user.dto.response.SignupTermResponse;
import com.gift.gift.domain.user.service.SignupPreflightService;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.exception.GlobalExceptionHandler;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SignupPreflightControllerTest {

    private SignupPreflightService signupPreflightService;
    private MockMvc mockMvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        signupPreflightService = mock(SignupPreflightService.class);

        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new SignupPreflightController(signupPreflightService)
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
    @DisplayName("약관 조회에 성공하면 정수 버전과 공통 성공 응답을 반환한다")
    void getSignupTerms_returnsSuccessResponse() throws Exception {
        Term term = new Term(
                "PRIVACY_COLLECTION_USE",
                3,
                "개인정보 수집 및 이용 동의서",
                "약관 본문",
                true
        );

        when(signupPreflightService.getSignupTerms())
                .thenReturn(
                        SignupTermsResponse.from(
                                List.of(SignupTermResponse.from(term))
                        )
                );

        mockMvc.perform(get("/auth/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("회원가입 약관을 조회했습니다."))
                .andExpect(jsonPath("$.data.terms[0].version").value(3))
                .andExpect(jsonPath("$.data.terms[0].version").isNumber())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("약관이 없으면 404와 약관 없음 코드를 반환한다")
    void getSignupTerms_returns404_whenTermsAreMissing() throws Exception {
        when(signupPreflightService.getSignupTerms())
                .thenThrow(
                        new BusinessException(
                                ErrorCode.SIGNUP_TERMS_NOT_FOUND
                        )
                );

        mockMvc.perform(get("/auth/terms"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code")
                        .value("SIGNUP_TERMS_NOT_FOUND"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.error.details").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("이메일을 정규화하고 사용 가능 응답을 반환한다")
    void checkEmailAvailability_normalizesEmailAndReturnsTrue()
            throws Exception {
        when(signupPreflightService.checkEmailAvailability("user@example.com"))
                .thenReturn(EmailAvailabilityResponse.from(true));

        mockMvc.perform(
                        post("/auth/email-availability")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"email":"  User@Example.com  "}
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("사용할 수 있는 이메일입니다."))
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(signupPreflightService)
                .checkEmailAvailability("user@example.com");
    }

    @Test
    @DisplayName("중복 이메일도 200과 사용 불가 응답을 반환한다")
    void checkEmailAvailability_returns200_whenEmailExists()
            throws Exception {
        when(signupPreflightService.checkEmailAvailability("user@example.com"))
                .thenReturn(EmailAvailabilityResponse.from(false));

        mockMvc.perform(
                        post("/auth/email-availability")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"email":"user@example.com"}
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("이미 사용 중인 이메일입니다."))
                .andExpect(jsonPath("$.data.available").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"email\":null}",
            "{\"email\":\"\"}",
            "{\"email\":\"   \"}",
            "{\"email\":\"not-an-email\"}"
    })
    @DisplayName("유효하지 않은 이메일은 전용 코드로 거부하고 조회하지 않는다")
    void checkEmailAvailability_returns400_whenEmailIsInvalid(
            String body
    ) throws Exception {
        mockMvc.perform(
                        post("/auth/email-availability")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_EMAIL_FORMAT"))
                .andExpect(jsonPath("$.error.details").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(signupPreflightService);
    }

    @Test
    @DisplayName("최대 길이를 초과한 이메일은 전용 코드로 거부한다")
    void checkEmailAvailability_returns400_whenEmailIsTooLong()
            throws Exception {
        String email = "a".repeat(255) + "@example.com";
        String body = "{\"email\":\"" + email + "\"}";

        mockMvc.perform(
                        post("/auth/email-availability")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_EMAIL_FORMAT"))
                .andExpect(jsonPath("$.error.details").doesNotExist());

        verifyNoInteractions(signupPreflightService);
    }

    @Test
    @DisplayName("잘못된 JSON은 details 없는 공통 요청 오류로 처리한다")
    void checkEmailAvailability_returns400_whenJsonIsMalformed()
            throws Exception {
        mockMvc.perform(
                        post("/auth/email-availability")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details").doesNotExist());

        verifyNoInteractions(signupPreflightService);
    }

    @Test
    @DisplayName("예상하지 못한 조회 오류는 공통 서버 오류로 처리한다")
    void getSignupTerms_returns500_whenUnexpectedErrorOccurs()
            throws Exception {
        when(signupPreflightService.getSignupTerms())
                .thenThrow(new IllegalStateException("test failure"));

        mockMvc.perform(get("/auth/terms"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
