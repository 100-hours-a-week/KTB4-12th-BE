package com.gift.gift.domain.user.dto.request;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.gift.gift.support.TestValidatorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignupRequestTest {

    private static LocalValidatorFactoryBean validator;

    @BeforeAll
    static void setUpValidator() {
        validator = TestValidatorFactory.create();
    }

    @AfterAll
    static void closeValidator() {
        validator.close();
    }

    @Test
    @DisplayName("정상 입력을 허용하고 이메일을 정규화한다")
    void validate_acceptsValidRequestAndNormalizesEmail() {
        SignupRequest request = request(
                "김선물", "2000-01-01", "  USER@Example.com  ", "Password1!"
        );

        assertTrue(validator.validate(request).isEmpty());
        assertEquals("user@example.com", request.email());
        assertEquals("SignupRequest[REDACTED]", request.toString());
    }

    @ParameterizedTest
    @CsvSource({"김선물1", "김선물!"})
    @DisplayName("이름의 숫자와 특수문자를 거부한다")
    void validate_rejectsInvalidName(String name) {
        assertViolation(
                request(name, "2000-01-01", "user@example.com", "Password1!"),
                "name", "INVALID_FORMAT"
        );
    }

    @Test
    @DisplayName("이름 30자는 허용하고 31자는 거부한다")
    void validate_checksNameLengthBoundary() {
        assertTrue(validator.validate(request(
                "가".repeat(30), "2000-01-01", "user@example.com", "Password1!"
        )).isEmpty());

        assertViolation(
                request("가".repeat(31), "2000-01-01",
                        "user@example.com", "Password1!"),
                "name", "MAX_LENGTH_EXCEEDED"
        );
    }

    @ParameterizedTest
    @CsvSource({
            "Abc12!x, TOO_SHORT",
            "abcdefgh, INVALID_FORMAT",
            "Abcdefg1, INVALID_FORMAT",
            "Abcdefg!, INVALID_FORMAT"
    })
    @DisplayName("비밀번호 길이와 문자 구성 오류를 거부한다")
    void validate_rejectsInvalidPassword(String password, String reason) {
        assertViolation(
                request("김선물", "2000-01-01", "user@example.com", password),
                "password", reason
        );
    }

    @Test
    @DisplayName("비밀번호 8자와 64자는 허용하고 65자는 거부한다")
    void validate_checksPasswordLengthBoundary() {
        assertTrue(validator.validate(request(
                "김선물", "2000-01-01", "user@example.com", "Abcdef1!"
        )).isEmpty());

        assertTrue(validator.validate(request(
                "김선물", "2000-01-01", "user@example.com",
                "A1!" + "a".repeat(61)
        )).isEmpty());

        assertViolation(
                request("김선물", "2000-01-01", "user@example.com",
                        "A1!" + "a".repeat(62)),
                "password", "TOO_LONG"
        );
    }

    @ParameterizedTest
    @CsvSource({"2000-02-30", "2000-1-01", "1900-02-29"})
    @DisplayName("잘못된 날짜 형식과 존재하지 않는 날짜를 거부한다")
    void validate_rejectsInvalidCalendarDate(String birth) {
        assertViolation(
                request("김선물", birth, "user@example.com", "Password1!"),
                "birth", "INVALID_FORMAT"
        );
    }

    @ParameterizedTest
    @CsvSource({
            "2026-09-19, OUT_OF_RANGE",
            "1906-09-17, OUT_OF_RANGE",
            "2012-09-19, AGE_REQUIREMENT_NOT_MET"
    })
    @DisplayName("생년월일의 날짜 범위와 만 14세 정책을 검증한다")
    void validate_rejectsBirthPolicyViolation(String birth, String reason) {
        assertViolation(
                request("김선물", birth, "user@example.com", "Password1!"),
                "birth", reason
        );
    }

    @ParameterizedTest
    @CsvSource({"2012-09-18", "1906-09-18", "2000-02-29"})
    @DisplayName("만 14세와 120년 경계에 포함되는 생년월일을 허용한다")
    void validate_acceptsBirthPolicyBoundary(String birth) {
        assertTrue(validator.validate(request(
                "김선물", birth, "user@example.com", "Password1!"
        )).isEmpty());
    }

    @Test
    @DisplayName("중복 약관 ID는 목록 검증 오류로 처리한다")
    void validate_rejectsDuplicateTermIds() {
        SignupRequest request = new SignupRequest(
                "김선물", "2000-01-01", "user@example.com", "Password1!",
                List.of(
                        new SignupTermConsentRequest(1L, 1, true),
                        new SignupTermConsentRequest(1L, 1, true)
                )
        );

        assertViolation(request, "termConsents", "DUPLICATE_TERM_ID");
    }

    @Test
    @DisplayName("중첩 약관 버전은 양수여야 한다")
    void validate_rejectsNonPositiveConsentVersion() {
        SignupRequest request = new SignupRequest(
                "김선물", "2000-01-01", "user@example.com", "Password1!",
                List.of(new SignupTermConsentRequest(1L, 0, true))
        );

        assertViolation(request, "termConsents[0].version", "OUT_OF_RANGE");
    }

    @Test
    @DisplayName("필수 입력 누락을 거부한다")
    void validate_rejectsMissingRequiredFields() {
        SignupRequest request = new SignupRequest(null, null, null, null, null);

        for (String field : List.of(
                "name", "birth", "email", "password", "termConsents"
        )) {
            assertViolation(request, field, "REQUIRED");
        }
    }

    private SignupRequest request(
            String name, String birth, String email, String password
    ) {
        return new SignupRequest(
                name, birth, email, password,
                List.of(new SignupTermConsentRequest(1L, 1, true))
        );
    }

    private void assertViolation(
            SignupRequest request, String field, String reason
    ) {
        assertTrue(validator.validate(request).stream().anyMatch(violation ->
                field.equals(violation.getPropertyPath().toString())
                        && reason.equals(violation.getMessage())
        ));
    }
}
