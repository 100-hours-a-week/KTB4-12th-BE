package com.gift.gift.domain.user.entity;

import java.time.LocalDate;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;
    private static String passwordHash;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
        passwordHash = new BCryptPasswordEncoder(12).encode("Password1!");
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("신규 회원은 활성 상태이며 생일 비공개와 최초 로그인 상태로 생성된다")
    void create_initializesDefaultState() {
        User user = newUser("User@Example.com", passwordHash, "김선물");

        assertEquals("user@example.com", user.getEmail());
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertTrue(user.isActive());
        assertFalse(user.isBirthdayPublic());
        assertTrue(user.isFirstLogin());
        assertTrue(validator.validate(user).isEmpty());
    }

    @Test
    @DisplayName("온보딩 완료는 최초 호출에서만 상태를 변경한다")
    void completeOnboarding_changesStateOnlyOnce() {
        User user = newUser("user@example.com", passwordHash, "김선물");

        assertTrue(user.completeOnboarding());
        assertFalse(user.isFirstLogin());

        assertFalse(user.completeOnboarding());
        assertFalse(user.isFirstLogin());
    }

    @Test
    @DisplayName("원문 비밀번호는 저장 가능한 비밀번호 해시로 인정하지 않는다")
    void validate_rejectsRawPassword() {
        User user = newUser("user@example.com", "Password1!", "김선물");

        boolean invalidHash = validator.validate(user).stream()
                .anyMatch(violation ->
                        violation.getPropertyPath().toString().equals("passwordHash")
                );

        assertTrue(invalidHash);
    }

    @Test
    @DisplayName("이름의 앞뒤 공백은 자동으로 보정하지 않고 거부한다")
    void validate_rejectsNameWithOuterSpaces() {
        User user = newUser("user@example.com", passwordHash, " 김선물 ");

        boolean invalidName = validator.validate(user).stream()
                .anyMatch(violation ->
                        violation.getPropertyPath().toString().equals("name")
                );

        assertTrue(invalidName);
    }

    @Test
    @DisplayName("30자를 초과하는 이름은 거부한다")
    void validate_rejectsNameLongerThanThirtyCharacters() {
        User user = newUser("user@example.com", passwordHash, "가".repeat(31));

        boolean invalidName = validator.validate(user).stream()
                .anyMatch(violation ->
                        violation.getPropertyPath().toString().equals("name")
                );

        assertTrue(invalidName);
    }

    private User newUser(String email, String hash, String name) {
        return new User(
                email,
                hash,
                name,
                LocalDate.of(2000, 1, 1)
        );
    }
}
