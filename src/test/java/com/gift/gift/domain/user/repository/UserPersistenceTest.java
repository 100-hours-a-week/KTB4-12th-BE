package com.gift.gift.domain.user.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.user.entity.Term;
import com.gift.gift.domain.user.entity.TermConsent;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class UserPersistenceTest {

    private static String passwordHash;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TermRepository termRepository;

    @Autowired
    private TermConsentRepository termConsentRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeAll
    static void initializePasswordHash() {
        passwordHash = new BCryptPasswordEncoder(12).encode("Password1!");
    }

    @Test
    @DisplayName("회원 저장 시 이메일이 정규화되고 기본 상태와 감사 시각이 기록된다")
    void saveUser_normalizesEmailAndInitializesStateAndAuditTimes() {
        String email = uniqueEmail();

        User saved = userRepository.saveAndFlush(
                newUser("  " + email.toUpperCase(java.util.Locale.ROOT) + "  ")
        );

        Long userId = saved.getId();

        entityManager.clear();

        User found = userRepository.findById(userId).orElseThrow();

        assertEquals(email, found.getEmail());
        assertEquals(passwordHash, found.getPasswordHash());
        assertEquals("김선물", found.getName());
        assertEquals(LocalDate.of(2000, 1, 1), found.getBirth());

        assertEquals(UserStatus.ACTIVE, found.getStatus());
        assertTrue(found.isActive());
        assertFalse(found.isBirthdayPublic());
        assertTrue(found.isFirstLogin());

        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
        assertFalse(found.getUpdatedAt().isBefore(found.getCreatedAt()));
    }

    @Test
    @DisplayName("저장한 회원은 이메일과 활성 상태 조건으로 조회할 수 있다")
    void findUser_returnsUserByEmailAndActiveStatus() {
        String email = uniqueEmail();
        User saved = userRepository.saveAndFlush(newUser(email));

        Long userId = saved.getId();

        entityManager.clear();

        User foundByEmail = userRepository.findByEmail(email).orElseThrow();

        User foundByStatus = userRepository
                .findByIdAndStatusAndDeletedAtIsNull(userId, UserStatus.ACTIVE)
                .orElseThrow();

        assertEquals(userId, foundByEmail.getId());
        assertEquals(userId, foundByStatus.getId());
        assertTrue(userRepository.existsByEmail(email));

        assertTrue(
                userRepository
                        .findByIdAndStatusAndDeletedAtIsNull(userId, UserStatus.DELETED)
                        .isEmpty()
        );
    }

    @Test
    @DisplayName("존재하지 않는 이메일은 조회 결과와 존재 여부가 모두 비어 있다")
    void findUser_returnsEmptyWhenEmailDoesNotExist() {
        String email = uniqueEmail();

        assertTrue(userRepository.findByEmail(email).isEmpty());
        assertFalse(userRepository.existsByEmail(email));
    }

    @Test
    @DisplayName("정규화 후 동일한 이메일의 회원은 중복 저장할 수 없다")
    void saveUser_rejectsDuplicateNormalizedEmail() {
        String email = uniqueEmail();

        userRepository.saveAndFlush(newUser(email));

        String duplicateEmail =
                "  " + email.toUpperCase(java.util.Locale.ROOT) + "  ";

        assertThrows(
                DataIntegrityViolationException.class,
                () -> userRepository.saveAndFlush(newUser(duplicateEmail))
        );
    }

    @Test
    @DisplayName("온보딩 상태 변경 후 생성 시각은 유지되고 수정 시각이 기록된다")
    void updateUser_preservesCreatedAtAndRecordsUpdatedAt() {
        User saved = userRepository.saveAndFlush(newUser(uniqueEmail()));
        Long userId = saved.getId();

        entityManager.clear();

        User user = userRepository.findById(userId).orElseThrow();

        LocalDateTime originalCreatedAt = user.getCreatedAt();
        LocalDateTime originalUpdatedAt = user.getUpdatedAt();

        assertTrue(user.completeOnboarding());

        entityManager.flush();
        entityManager.clear();

        User updated = userRepository.findById(userId).orElseThrow();

        assertFalse(updated.isFirstLogin());
        assertEquals(originalCreatedAt, updated.getCreatedAt());
        assertNotNull(updated.getUpdatedAt());
        assertFalse(updated.getUpdatedAt().isBefore(originalUpdatedAt));
    }

    @Test
    @DisplayName("약관은 코드와 버전 및 감사 시각을 저장하고 조회할 수 있다")
    void saveTerm_persistsFieldsAndAuditTimes() {
        String termCode = uniqueTermCode();

        Term saved = termRepository.saveAndFlush(
                newTerm(termCode, "1.0.0")
        );

        Long termId = saved.getId();

        entityManager.clear();

        Term found = termRepository.findById(termId).orElseThrow();

        assertEquals(termCode, found.getTermCode());
        assertEquals("1.0.0", found.getVersion());
        assertEquals("테스트 약관", found.getTitle());
        assertEquals("테스트 약관 본문", found.getContent());
        assertTrue(found.isRequired());

        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
    }

    @Test
    @DisplayName("동일한 약관 코드와 버전은 중복 저장할 수 없다")
    void saveTerm_rejectsDuplicateCodeAndVersion() {
        String termCode = uniqueTermCode();

        termRepository.saveAndFlush(newTerm(termCode, "1.0.0"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> termRepository.saveAndFlush(
                        newTerm(termCode, "1.0.0")
                )
        );
    }

    @Test
    @DisplayName("동일한 약관 코드라도 버전이 다르면 각각 저장할 수 있다")
    void saveTerm_allowsDifferentVersionsOfSameCode() {
        String termCode = uniqueTermCode();

        Term first = termRepository.saveAndFlush(
                newTerm(termCode, "1.0.0")
        );

        Term second = termRepository.saveAndFlush(
                newTerm(termCode, "2.0.0")
        );

        Long firstId = first.getId();
        Long secondId = second.getId();

        entityManager.clear();

        Term firstFound = termRepository.findById(firstId).orElseThrow();
        Term secondFound = termRepository.findById(secondId).orElseThrow();

        assertEquals(termCode, firstFound.getTermCode());
        assertEquals(termCode, secondFound.getTermCode());
        assertEquals("1.0.0", firstFound.getVersion());
        assertEquals("2.0.0", secondFound.getVersion());
        assertFalse(firstId.equals(secondId));
    }

    @Test
    @DisplayName("약관 동의는 회원과 특정 약관 버전을 참조하고 감사 시각을 기록한다")
    void saveConsent_persistsRelationsAndAuditTimes() {
        User user = userRepository.saveAndFlush(newUser(uniqueEmail()));

        Term term = termRepository.saveAndFlush(
                newTerm(uniqueTermCode(), "1.0.0")
        );

        TermConsent saved = termConsentRepository.saveAndFlush(
                new TermConsent(user, term, true)
        );

        Long consentId = saved.getId();
        Long userId = user.getId();
        Long termId = term.getId();

        entityManager.clear();

        TermConsent found = termConsentRepository
                .findById(consentId)
                .orElseThrow();

        assertEquals(userId, found.getUser().getId());
        assertEquals(termId, found.getTerm().getId());
        assertEquals("1.0.0", found.getTerm().getVersion());
        assertTrue(found.isAgreed());

        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
    }

    @Test
    @DisplayName("같은 회원의 같은 약관 버전 동의는 중복 저장할 수 없다")
    void saveConsent_rejectsDuplicateUserAndTerm() {
        User user = userRepository.saveAndFlush(newUser(uniqueEmail()));

        Term term = termRepository.saveAndFlush(
                newTerm(uniqueTermCode(), "1.0.0")
        );

        termConsentRepository.saveAndFlush(
                new TermConsent(user, term, true)
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> termConsentRepository.saveAndFlush(
                        new TermConsent(user, term, true)
                )
        );
    }

    private User newUser(String email) {
        return new User(
                email,
                passwordHash,
                "김선물",
                LocalDate.of(2000, 1, 1)
        );
    }

    private Term newTerm(String termCode, String version) {
        return new Term(
                termCode,
                version,
                "테스트 약관",
                "테스트 약관 본문",
                true
        );
    }

    private String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }

    private String uniqueTermCode() {
        return "TEST_" + UUID.randomUUID().toString().replace("-", "");
    }
}
