package com.gift.gift.domain.user.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.gift.gift.domain.user.dto.request.SignupRequest;
import com.gift.gift.domain.user.dto.request.SignupTermConsentRequest;
import com.gift.gift.domain.user.entity.Term;
import com.gift.gift.domain.user.repository.TermConsentRepository;
import com.gift.gift.domain.user.repository.TermRepository;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class SignupPersistenceTest {

    @Autowired
    private SignupService service;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TermRepository termRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private TermConsentRepository consentRepository;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    private String email;
    private Long createdTermId;
    private List<Term> requiredTerms;

    @BeforeEach
    void setUp() {
        email = "signup-" + UUID.randomUUID() + "@example.com";

        Term term = termRepository.saveAndFlush(new Term(
                "TEST_" + UUID.randomUUID(),
                1,
                "테스트 약관",
                "테스트 본문",
                true
        ));

        createdTermId = term.getId();
        requiredTerms = termRepository.findCurrentRequiredTerms();
    }

    @AfterEach
    void cleanUp() {
        reset(consentRepository, passwordEncoder);

        jdbcTemplate.update("""
                DELETE FROM term_consents
                WHERE user_id IN (
                    SELECT id FROM users WHERE email = ?
                )
                """, email);

        jdbcTemplate.update("DELETE FROM users WHERE email = ?", email);

        if (createdTermId != null) {
            jdbcTemplate.update("DELETE FROM terms WHERE id = ?", createdTermId);
        }
    }

    @Test
    @DisplayName("회원과 필수 약관 동의를 실제 MySQL에 저장한다")
    void signup_persistsUserAndConsentsWithBcryptHash() {
        var response = service.signup(request());
        var user = userRepository.findById(response.userId()).orElseThrow();

        assertEquals(email, user.getEmail());
        assertNotEquals("Password1!", user.getPasswordHash());

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        assertTrue(encoder.matches("Password1!", user.getPasswordHash()));

        assertTrue(user.getPasswordHash().matches(
                "^\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}$"
        ));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM term_consents WHERE user_id = ?",
                Integer.class,
                user.getId()
        );

        assertEquals(requiredTerms.size(), count.intValue());

        Integer invalidCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM term_consents
                WHERE user_id = ?
                  AND (is_agreed = false OR created_at IS NULL)
                """, Integer.class, user.getId());

        assertEquals(0, invalidCount.intValue());
    }

    @Test
    @DisplayName("동의 저장 오류는 회원 저장까지 롤백한다")
    void signup_rollsBackUserWhenConsentSaveFails() {
        doThrow(new IllegalStateException("simulated persistence failure"))
                .when(consentRepository)
                .saveAllAndFlush(any());

        assertThrows(
                IllegalStateException.class,
                () -> service.signup(request())
        );

        assertFalse(userRepository.existsByEmail(email));
    }

    @Test
    @DisplayName("동시에 같은 이메일로 가입하면 하나만 성공한다")
    void signup_allowsOnlyOneConcurrentSignupForSameEmail() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);

        doAnswer(invocation -> {
            barrier.await(10, TimeUnit.SECONDS);
            String rawPassword = invocation.getArgument(0);
            return new BCryptPasswordEncoder(12).encode(rawPassword);
        }).when(passwordEncoder).encode(anyString());

        Callable<String> signup = () -> {
            try {
                service.signup(request());
                return "SUCCESS";
            } catch (BusinessException exception) {
                return exception.getErrorCode().code();
            }
        };

        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(signup);
            var second = executor.submit(signup);

            List<String> results = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );

            assertEquals(
                    1L,
                    results.stream().filter("SUCCESS"::equals).count()
            );

            assertEquals(
                    1L,
                    results.stream()
                            .filter(ErrorCode.EMAIL_ALREADY_IN_USE.code()::equals)
                            .count()
            );

            Integer userCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE email = ?",
                    Integer.class,
                    email
            );

            assertEquals(1, userCount.intValue());

            Integer consentCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM term_consents
                    WHERE user_id IN (
                        SELECT id FROM users WHERE email = ?
                    )
                    """, Integer.class, email);

            assertEquals(requiredTerms.size(), consentCount.intValue());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    private SignupRequest request() {
        List<SignupTermConsentRequest> consents = requiredTerms.stream()
                .map(term -> new SignupTermConsentRequest(
                        term.getId(),
                        term.getVersion(),
                        true
                ))
                .toList();

        return new SignupRequest(
                "김선물",
                "2000-01-01",
                email,
                "Password1!",
                consents
        );
    }
}
