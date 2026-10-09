package com.gift.gift.domain.user.dto.request;

import java.util.Set;

import tools.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CompleteOnboardingRequestTest {

    private ObjectMapper objectMapper;
    private ValidatorFactory validatorFactory;
    private Validator validator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        validatorFactory =
                Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterEach
    void tearDown() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("completed true 요청은 유효하다")
    void completedTrue_isValid() throws Exception {
        CompleteOnboardingRequest request = read("""
                {
                  "completed": true
                }
                """);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.hasCompleted()).isTrue();
        assertThat(request.rawCompleted().booleanValue()).isTrue();
    }

    @Test
    @DisplayName("completed를 누락하면 REQUIRED로 거부한다")
    void missingCompleted_isRequired() throws Exception {
        CompleteOnboardingRequest request = read("{}");

        assertFieldViolation(
                request,
                "completed",
                "REQUIRED"
        );
    }

    @Test
    @DisplayName("completed가 null이면 REQUIRED로 거부한다")
    void nullCompleted_isRequired() throws Exception {
        CompleteOnboardingRequest request = read("""
                {
                  "completed": null
                }
                """);

        assertFieldViolation(
                request,
                "completed",
                "REQUIRED"
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"true\"",
            "1",
            "{}",
            "[]"
    })
    @DisplayName("Boolean이 아닌 completed는 INVALID_FORMAT으로 거부한다")
    void nonBooleanCompleted_isInvalidFormat(
            String completed
    ) throws Exception {
        CompleteOnboardingRequest request = read(
                """
                {
                  "completed": %s
                }
                """.formatted(completed)
        );

        assertFieldViolation(
                request,
                "completed",
                "INVALID_FORMAT"
        );
    }

    @Test
    @DisplayName("completed false는 INVALID_VALUE로 거부한다")
    void completedFalse_isInvalidValue() throws Exception {
        CompleteOnboardingRequest request = read("""
                {
                  "completed": false
                }
                """);

        assertFieldViolation(
                request,
                "completed",
                "INVALID_VALUE"
        );
    }

    @Test
    @DisplayName("정의되지 않은 추가 필드는 객체 수준 오류로 거부한다")
    void unknownField_isRequestLevelViolation()
            throws Exception {
        CompleteOnboardingRequest request = read("""
                {
                  "completed": true,
                  "name": "변경 이름"
                }
                """);

        Set<ConstraintViolation<CompleteOnboardingRequest>>
                violations = validator.validate(request);

        assertThat(violations).singleElement()
                .satisfies(violation -> {
                    assertThat(
                            violation.getPropertyPath().toString()
                    ).isEmpty();
                    assertThat(violation.getMessage())
                            .isEqualTo("UNKNOWN_FIELD");
                });
    }

    private CompleteOnboardingRequest read(String json)
            throws Exception {
        return objectMapper.readValue(
                json,
                CompleteOnboardingRequest.class
        );
    }

    private void assertFieldViolation(
            CompleteOnboardingRequest request,
            String field,
            String reason
    ) {
        assertThat(validator.validate(request))
                .anySatisfy(violation -> {
                    assertThat(
                            violation.getPropertyPath().toString()
                    ).isEqualTo(field);
                    assertThat(violation.getMessage())
                            .isEqualTo(reason);
                });
    }
}
