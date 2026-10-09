package com.gift.gift.domain.user.entity;

import java.util.stream.Stream;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TermTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    static Stream<Arguments> invalidTerms() {
        return Stream.of(null, "", "   ").flatMap(blank -> Stream.of(
                Arguments.of(new Term(blank, 1, "제목", "본문", true), "termCode"),
                Arguments.of(new Term("CODE", -1, "제목", "본문", true), "version"),
                Arguments.of(new Term("CODE", 1, blank, "본문", true), "title"),
                Arguments.of(new Term("CODE", 1, "제목", blank, true), "content")
        ));
    }

    @ParameterizedTest
    @MethodSource("invalidTerms")
    @DisplayName("약관 코드·버전·제목·본문은 null 또는 빈 값일 수 없다")
    void validate_rejectsMissingRequiredField(Term term, String field) {
        assertInvalidField(term, field);
    }

    static Stream<Arguments> oversizedTerms() {
        return Stream.of(
                Arguments.of(new Term("C".repeat(51),  1, "제목", "본문", true), "termCode"),
                Arguments.of(new Term("CODE", 1, "가".repeat(201), "본문", true), "title")
        );
    }

    @ParameterizedTest
    @MethodSource("oversizedTerms")
    @DisplayName("약관 코드 50자·버전 20자·제목 200자를 초과하면 거부한다")
    void validate_rejectsFieldBeyondMaximumLength(Term term, String field) {
        assertInvalidField(term, field);
    }

    @Test
    @DisplayName("약관 코드·버전·제목의 최대 길이 경계값은 허용한다")
    void validate_acceptsMaximumLengths() {
        Term term = new Term("C".repeat(50), 1, "가".repeat(200), "본문", true);

        assertTrue(validator.validate(term).isEmpty());
    }

    private void assertInvalidField(Term term, String field) {
        assertTrue(validator.validate(term).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals(field)));
    }
}
