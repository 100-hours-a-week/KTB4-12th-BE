package com.gift.gift.domain.user.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.data.auditing.CurrentDateTimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
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

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuditingHandler auditingHandler;

    @AfterEach
    void restoreAuditClock() {
        auditingHandler.setDateTimeProvider(CurrentDateTimeProvider.INSTANCE);
    }

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
        LocalDateTime createdTime = LocalDateTime.of(2026, 1, 1, 12, 0);
        LocalDateTime modifiedTime = createdTime.plusMinutes(1);
        auditingHandler.setDateTimeProvider(() -> java.util.Optional.of(createdTime));
        User saved = userRepository.saveAndFlush(newUser(uniqueEmail()));
        Long userId = saved.getId();

        entityManager.clear();

        User user = userRepository.findById(userId).orElseThrow();

        LocalDateTime originalCreatedAt = user.getCreatedAt();
        LocalDateTime originalUpdatedAt = user.getUpdatedAt();

        assertEquals(createdTime, originalCreatedAt);
        assertEquals(createdTime, originalUpdatedAt);
        auditingHandler.setDateTimeProvider(() -> java.util.Optional.of(modifiedTime));

        assertTrue(user.completeOnboarding());

        entityManager.flush();
        entityManager.clear();

        User updated = userRepository.findById(userId).orElseThrow();

        assertFalse(updated.isFirstLogin());
        assertEquals(originalCreatedAt, updated.getCreatedAt());
        assertNotNull(updated.getUpdatedAt());
        assertEquals(modifiedTime, updated.getUpdatedAt());
        assertTrue(updated.getUpdatedAt().isAfter(originalUpdatedAt));
    }

    @Test
    @DisplayName("상태 변경 없이 flush하면 수정 시각도 유지된다")
    void flushUser_preservesUpdatedAtWhenUnchanged() {
        LocalDateTime initialTime = LocalDateTime.of(2026, 1, 1, 12, 0);
        auditingHandler.setDateTimeProvider(() -> java.util.Optional.of(initialTime));
        User saved = userRepository.saveAndFlush(newUser(uniqueEmail()));
        Long userId = saved.getId();
        entityManager.clear();
        userRepository.findById(userId).orElseThrow();
        auditingHandler.setDateTimeProvider(() -> java.util.Optional.of(initialTime.plusMinutes(1)));

        entityManager.flush();
        entityManager.clear();

        assertEquals(initialTime, userRepository.findById(userId).orElseThrow().getUpdatedAt());
    }

    @ParameterizedTest
    @CsvSource({"DELETED,false", "ACTIVE,true", "DELETED,true"})
    @DisplayName("활성 회원 조회는 삭제 상태 또는 삭제 시각이 있는 회원을 제외한다")
    void findActiveUser_excludesDeletedUsers(String status, boolean deleted) {
        User saved = userRepository.saveAndFlush(newUser(uniqueEmail()));
        Long userId = saved.getId();
        jdbcTemplate.update("UPDATE users SET status = ?, deleted_at = ? WHERE id = ?",
                status, deleted ? LocalDateTime.of(2026, 1, 1, 12, 0) : null, userId);
        entityManager.clear();

        assertTrue(userRepository.findByIdAndStatusAndDeletedAtIsNull(userId, UserStatus.ACTIVE).isEmpty());
        assertFalse(userRepository.findById(userId).orElseThrow().isActive());
    }

    @Test
    @DisplayName("같은 회원은 서로 다른 약관에 각각 동의를 저장할 수 있다")
    void saveConsent_allowsDifferentTermsForSameUser() {
        User user = userRepository.saveAndFlush(newUser(uniqueEmail()));
        Term first = termRepository.saveAndFlush(newTerm(uniqueTermCode(), 1));
        Term second = termRepository.saveAndFlush(newTerm(uniqueTermCode(), 1));
        Long firstConsentId = termConsentRepository.saveAndFlush(new TermConsent(user, first, true)).getId();
        Long secondConsentId = termConsentRepository.saveAndFlush(new TermConsent(user, second, false)).getId();
        entityManager.clear();

        TermConsent firstFound = termConsentRepository.findById(firstConsentId).orElseThrow();
        TermConsent secondFound = termConsentRepository.findById(secondConsentId).orElseThrow();
        assertEquals(user.getId(), firstFound.getUser().getId());
        assertEquals(user.getId(), secondFound.getUser().getId());
        assertEquals(first.getId(), firstFound.getTerm().getId());
        assertEquals(second.getId(), secondFound.getTerm().getId());
        assertTrue(firstFound.isAgreed());
        assertFalse(secondFound.isAgreed());
    }

    @ParameterizedTest
    @CsvSource({
            "users,email", "users,password", "users,name", "users,birth", "users,status",
            "users,is_birthday_public", "users,is_first_login", "users,created_at", "users,updated_at",
            "terms,term_code", "terms,version", "terms,title", "terms,content", "terms,is_required",
            "terms,created_at", "terms,updated_at", "term_consents,user_id", "term_consents,term_id",
            "term_consents,is_agreed", "term_consents,created_at", "term_consents,updated_at"
    })
    @DisplayName("실제 MySQL 필수 컬럼은 NOT NULL이며 직접 SQL로도 null 변경을 거부한다")
    void mysql_rejectsNullForRequiredColumns(String table, String column) {
        Long rowId = switch (table) {
            case "users" -> userRepository.saveAndFlush(newUser(uniqueEmail())).getId();
            case "terms" -> termRepository.saveAndFlush(newTerm(uniqueTermCode(), 1)).getId();
            case "term_consents" -> newPersistedConsent().getId();
            default -> throw new IllegalArgumentException("Unknown test table");
        };
        String nullable = jdbcTemplate.queryForObject("""
                SELECT IS_NULLABLE FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """, String.class, table, column);
        assertEquals("NO", nullable);

        // 테이블·컬럼은 위의 고정 테스트 목록만 사용하며 JPA Validation을 우회해 DB 제약을 검증한다.
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("UPDATE " + table + " SET " + column + " = NULL WHERE id = ?", rowId));
    }

    @ParameterizedTest
    @CsvSource({"user_id,users", "term_id,terms"})
    @DisplayName("실제 MySQL FK는 올바른 부모를 참조하고 존재하지 않는 부모 ID를 거부한다")
    void mysql_rejectsNonexistentConsentParent(String column, String parentTable) {
        TermConsent consent = newPersistedConsent();
        String referencedTable = jdbcTemplate.queryForObject("""
                SELECT REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'term_consents'
                  AND COLUMN_NAME = ? AND REFERENCED_TABLE_NAME IS NOT NULL
                """, String.class, column);
        assertEquals(parentTable, referencedTable);
        assertEquals("id", jdbcTemplate.queryForObject("""
                SELECT REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'term_consents'
                  AND COLUMN_NAME = ? AND REFERENCED_TABLE_NAME IS NOT NULL
                """, String.class, column));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + parentTable + " WHERE id = ?", Integer.class, Long.MIN_VALUE));

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("UPDATE term_consents SET " + column + " = ? WHERE id = ?",
                        Long.MIN_VALUE, consent.getId()));
    }

    private TermConsent newPersistedConsent() {
        User user = userRepository.saveAndFlush(newUser(uniqueEmail()));
        Term term = termRepository.saveAndFlush(newTerm(uniqueTermCode(), 1));
        return termConsentRepository.saveAndFlush(new TermConsent(user, term, true));
    }

    @Test
    @DisplayName("약관은 코드와 버전 및 감사 시각을 저장하고 조회할 수 있다")
    void saveTerm_persistsFieldsAndAuditTimes() {
        String termCode = uniqueTermCode();

        Term saved = termRepository.saveAndFlush(
                newTerm(termCode, 1)
        );

        Long termId = saved.getId();

        entityManager.clear();

        Term found = termRepository.findById(termId).orElseThrow();

        assertEquals(termCode, found.getTermCode());
        assertEquals(1, found.getVersion());
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

        termRepository.saveAndFlush(newTerm(termCode, 1));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> termRepository.saveAndFlush(
                        newTerm(termCode, 1)
                )
        );
    }

    @Test
    @DisplayName("동일한 약관 코드라도 버전이 다르면 각각 저장할 수 있다")
    void saveTerm_allowsDifferentVersionsOfSameCode() {
        String termCode = uniqueTermCode();

        Term first = termRepository.saveAndFlush(
                newTerm(termCode, 1)
        );

        Term second = termRepository.saveAndFlush(
                newTerm(termCode, 2)
        );

        Long firstId = first.getId();
        Long secondId = second.getId();

        entityManager.clear();

        Term firstFound = termRepository.findById(firstId).orElseThrow();
        Term secondFound = termRepository.findById(secondId).orElseThrow();

        assertEquals(termCode, firstFound.getTermCode());
        assertEquals(termCode, secondFound.getTermCode());
        assertEquals(1, firstFound.getVersion());
        assertEquals(2, secondFound.getVersion());
        assertFalse(firstId.equals(secondId));
    }

    @Test
    @DisplayName("약관 동의는 회원과 특정 약관 버전을 참조하고 감사 시각을 기록한다")
    void saveConsent_persistsRelationsAndAuditTimes() {
        User user = userRepository.saveAndFlush(newUser(uniqueEmail()));

        Term term = termRepository.saveAndFlush(
                newTerm(uniqueTermCode(), 1)
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
        assertEquals(1, found.getTerm().getVersion());
        assertTrue(found.isAgreed());

        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
    }

    @Test
    @DisplayName("같은 회원의 같은 약관 버전 동의는 중복 저장할 수 없다")
    void saveConsent_rejectsDuplicateUserAndTerm() {
        User user = userRepository.saveAndFlush(newUser(uniqueEmail()));

        Term term = termRepository.saveAndFlush(
                newTerm(uniqueTermCode(), 1)
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

    private Term newTerm(String termCode, int version) {
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
