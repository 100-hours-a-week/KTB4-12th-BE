package com.gift.gift.domain.user.dto.request;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.support.TestValidatorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = TestValidatorFactory.create();
    }

    @Test
    @DisplayName("로그인 이메일을 앞뒤 공백 제거 후 소문자로 정규화한다")
    void constructor_normalizesEmail() {
        LoginRequest request = new LoginRequest(
                "  USER@Example.COM  ",
                "Password1!"
        );

        assertEquals(
                "user@example.com",
                request.email()
        );
    }

    @Test
    @DisplayName("이메일이 비어 있으면 REQUIRED 오류가 발생한다")
    void validate_rejectsBlankEmail() {
        LoginRequest request = new LoginRequest(
                " ",
                "Password1!"
        );

        assertViolation(
                validator.validate(request),
                "email",
                "REQUIRED"
        );
    }

    @Test
    @DisplayName("이메일 형식이 아니면 INVALID_FORMAT 오류가 발생한다")
    void validate_rejectsInvalidEmail() {
        LoginRequest request = new LoginRequest(
                "invalid-email",
                "Password1!"
        );

        assertViolation(
                validator.validate(request),
                "email",
                "INVALID_FORMAT"
        );
    }

    @Test
    @DisplayName("비밀번호가 비어 있으면 REQUIRED 오류가 발생한다")
    void validate_rejectsBlankPassword() {
        LoginRequest request = new LoginRequest(
                "user@example.com",
                " "
        );

        assertViolation(
                validator.validate(request),
                "password",
                "REQUIRED"
        );
    }

    @Test
    @DisplayName("비밀번호가 8자보다 짧으면 TOO_SHORT 오류가 발생한다")
    void validate_rejectsShortPassword() {
        LoginRequest request = new LoginRequest(
                "user@example.com",
                "Pass1!"
        );

        assertViolation(
                validator.validate(request),
                "password",
                "TOO_SHORT"
        );
    }

    @Test
    @DisplayName("영문 숫자 특수문자 조합이 아니면 INVALID_FORMAT 오류가 발생한다")
    void validate_rejectsInvalidPasswordFormat() {
        LoginRequest request = new LoginRequest(
                "user@example.com",
                "password"
        );

        assertViolation(
                validator.validate(request),
                "password",
                "INVALID_FORMAT"
        );
    }

    @Test
    @DisplayName("올바른 이메일과 비밀번호는 검증을 통과한다")
    void validate_acceptsValidRequest() {
        LoginRequest request = new LoginRequest(
                "user@example.com",
                "Password1!"
        );

        assertTrue(
                validator.validate(request).isEmpty()
        );
    }

    private void assertViolation(
            Set<ConstraintViolation<LoginRequest>> violations,
            String field,
            String reason
    ) {
        assertTrue(
                violations.stream().anyMatch(
                        violation ->
                                violation
                                        .getPropertyPath()
                                        .toString()
                                        .equals(field)
                                        && violation
                                        .getMessage()
                                        .equals(reason)
                )
        );
    }
}
