package com.gift.gift.domain.user.dto.request;

import java.util.Set;

import tools.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.gift.gift.support.TestValidatorFactory;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateUserProfileRequestTest {

    private static LocalValidatorFactoryBean validator;

    private ObjectMapper objectMapper;

    @BeforeAll
    static void setUpValidator() {
        validator = TestValidatorFactory.create();
    }

    @AfterAll
    static void closeValidator() {
        validator.close();
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("생년월일만 전달한 부분 수정 요청은 유효하다")
    void birthOnly_isValid() throws Exception {
        UpdateUserProfileRequest request = read("""
                {
                  "birth": "1998-03-15"
                }
                """);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.hasBirth()).isTrue();
        assertThat(request.hasBirthdayPublic()).isFalse();
        assertThat(request.birthValue())
                .isEqualTo("1998-03-15");
    }

    @Test
    @DisplayName("생일 공개 여부 false도 전달된 필드로 인식한다")
    void falseBirthdayPublic_isValid() throws Exception {
        UpdateUserProfileRequest request = read("""
                {
                  "isBirthdayPublic": false
                }
                """);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.hasBirthdayPublic()).isTrue();
        assertThat(request.birthdayPublicValue()).isFalse();
    }

    @Test
    @DisplayName("빈 객체는 EMPTY_UPDATE_FIELDS로 거부한다")
    void emptyObject_isInvalid() throws Exception {
        UpdateUserProfileRequest request = read("{}");

        Set<ConstraintViolation<UpdateUserProfileRequest>> violations =
                validator.validate(request);

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("EMPTY_UPDATE_FIELDS");
    }

    @Test
    @DisplayName("명시적인 null은 REQUIRED로 거부한다")
    void explicitNull_isInvalid() throws Exception {
        UpdateUserProfileRequest request = read("""
                {
                  "birth": null
                }
                """);

        assertViolation(request, "birth", "REQUIRED");
    }

    @Test
    @DisplayName("Boolean이 아닌 공개 설정은 INVALID_TYPE으로 거부한다")
    void nonBooleanBirthdayPublic_isInvalid() throws Exception {
        UpdateUserProfileRequest request = read("""
                {
                  "isBirthdayPublic": "true"
                }
                """);

        assertViolation(
                request,
                "isBirthdayPublic",
                "INVALID_TYPE"
        );
    }

    @Test
    @DisplayName("YYYY-MM-DD가 아닌 날짜는 INVALID_FORMAT으로 거부한다")
    void invalidBirthFormat_isInvalid() throws Exception {
        UpdateUserProfileRequest request = read("""
                {
                  "birth": "03-15"
                }
                """);

        assertViolation(
                request,
                "birth",
                "INVALID_FORMAT"
        );
    }

    @Test
    @DisplayName("존재하지 않는 날짜는 INVALID_DATE로 거부한다")
    void impossibleBirth_isInvalid() throws Exception {
        UpdateUserProfileRequest request = read("""
                {
                  "birth": "2026-02-30"
                }
                """);

        assertViolation(
                request,
                "birth",
                "INVALID_DATE"
        );
    }

    @ParameterizedTest
    @CsvSource({
            "2099-12-31, OUT_OF_RANGE",
            "2026-09-19, OUT_OF_RANGE",
            "1906-09-17, OUT_OF_RANGE",
            "2025-05-05, AGE_REQUIREMENT_NOT_MET",
            "2012-09-19, AGE_REQUIREMENT_NOT_MET"
    })
    @DisplayName("생년월일의 날짜 범위와 만 14세 정책을 검증한다")
    void birthPolicyViolation_isInvalid(
            String birth,
            String reason
    ) throws Exception {
        UpdateUserProfileRequest request = read(
                """
                {
                  "birth": "%s"
                }
                """.formatted(birth)
        );

        assertViolation(request, "birth", reason);
    }

    @ParameterizedTest
    @CsvSource({
            "2012-09-18",
            "1906-09-18"
    })
    @DisplayName("만 14세와 120년 경계에 포함되는 생년월일을 허용한다")
    void birthPolicyBoundary_isValid(String birth)
            throws Exception {
        UpdateUserProfileRequest request = read(
                """
                {
                  "birth": "%s"
                }
                """.formatted(birth)
        );

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("허용되지 않은 필드는 UNKNOWN_FIELD로 거부한다")
    void unknownField_isInvalid() throws Exception {
        UpdateUserProfileRequest request = read("""
                {
                  "name": "변경 이름"
                }
                """);

        assertViolation(
                request,
                "name",
                "UNKNOWN_FIELD"
        );
    }

    private UpdateUserProfileRequest read(String json)
            throws Exception {
        return objectMapper.readValue(
                json,
                UpdateUserProfileRequest.class
        );
    }

    private void assertViolation(
            UpdateUserProfileRequest request,
            String field,
            String reason
    ) {
        assertThat(validator.validate(request))
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath().toString())
                            .isEqualTo(field);
                    assertThat(violation.getMessage())
                            .isEqualTo(reason);
                });
    }
}
