package com.gift.gift.domain.user.repository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserSession;
import com.gift.gift.domain.user.service.LoginSessionService;
import com.gift.gift.global.security.RefreshTokenProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=update")
@Import(UserSessionPersistenceTest.FixedClockConfig.class)
class UserSessionPersistenceTest {

    private static final LocalDateTime BASE_TIME =
            LocalDateTime.of(2026, 9, 21, 12, 0);

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            ZoneId zoneId = ZoneId.of("Asia/Seoul");

            return Clock.fixed(
                    BASE_TIME.atZone(zoneId).toInstant(),
                    zoneId
            );
        }
    }

    private final List<Long> createdUserIds = new ArrayList<>();

    @Autowired
    private UserSessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LoginSessionService loginSessionService;

    @Autowired
    private RefreshTokenProvider refreshTokenProvider;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        sessionRepository.deleteAll();
        createdUserIds.forEach(userRepository::deleteById);
        createdUserIds.clear();
    }

    @Test
    @DisplayName("Refresh Token은 원문이 아니라 SHA-256 해시로 저장한다")
    void saveSession_persistsRefreshTokenHashOnly() {
        User user = saveUser();
        String rawToken = validToken('a');
        String tokenHash = refreshTokenProvider.hash(rawToken);

        UserSession saved = saveSession(
                user,
                tokenHash,
                BASE_TIME,
                BASE_TIME.plusDays(14)
        );

        String stored = jdbcTemplate.queryForObject(
                "SELECT refresh_token FROM user_sessions WHERE id = ?",
                String.class,
                saved.getId()
        );

        assertEquals(tokenHash, stored);
        assertNotEquals(rawToken, stored);
        assertFalse(stored.contains(rawToken));
    }

    @Test
    @DisplayName("동일한 Refresh Token 해시는 UNIQUE 제약으로 중복 저장을 거부한다")
    void saveSession_rejectsDuplicateRefreshTokenHash() {
        User firstUser = saveUser();
        User secondUser = saveUser();
        String tokenHash = refreshTokenProvider.hash(validToken('a'));

        saveSession(
                firstUser,
                tokenHash,
                BASE_TIME,
                BASE_TIME.plusDays(14)
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> saveSession(
                        secondUser,
                        tokenHash,
                        BASE_TIME,
                        BASE_TIME.plusDays(14)
                )
        );
    }

    @Test
    @DisplayName("user_sessions의 user_id는 users를 참조하는 FK이며 잘못된 회원을 거부한다")
    void saveSession_enforcesUserForeignKey() {
        String referencedTable = jdbcTemplate.queryForObject("""
                SELECT REFERENCED_TABLE_NAME
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'user_sessions'
                  AND COLUMN_NAME = 'user_id'
                  AND REFERENCED_TABLE_NAME IS NOT NULL
                """, String.class);

        assertEquals("users", referencedTable);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("""
                        INSERT INTO user_sessions (
                            user_id,
                            refresh_token,
                            authenticated_at,
                            expires_at,
                            created_at,
                            updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        Long.MIN_VALUE,
                        refreshTokenProvider.hash(validToken('f')),
                        BASE_TIME,
                        BASE_TIME.plusDays(14),
                        BASE_TIME,
                        BASE_TIME
                )
        );
    }

    @Test
    @DisplayName("expires_at은 authenticated_at보다 이후여야 한다는 MySQL CHECK를 적용한다")
    void saveSession_enforcesExpirationCheckConstraint() {
        User user = saveUser();

        Integer constraintCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'user_sessions'
                  AND CONSTRAINT_NAME = 'chk_user_sessions_expires_after_auth'
                  AND CONSTRAINT_TYPE = 'CHECK'
                """, Integer.class);

        assertEquals(1, constraintCount);

        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update("""
                        INSERT INTO user_sessions (
                            user_id,
                            refresh_token,
                            authenticated_at,
                            expires_at,
                            created_at,
                            updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        user.getId(),
                        refreshTokenProvider.hash(validToken('c')),
                        BASE_TIME,
                        BASE_TIME,
                        BASE_TIME,
                        BASE_TIME
                )
        );
    }

    @Test
    @DisplayName("저장 후 조회한 만료 폐기 삭제 세션은 모두 비활성 상태다")
    void findSession_restoresExpiredRevokedAndDeletedState() {
        User user = saveUser();

        UserSession expired = saveSession(
                user,
                refreshTokenProvider.hash(validToken('e')),
                BASE_TIME.minusDays(15),
                BASE_TIME.minusSeconds(1)
        );

        UserSession revoked = new UserSession(
                user,
                refreshTokenProvider.hash(validToken('r')),
                BASE_TIME.minusDays(1),
                BASE_TIME.plusDays(13)
        );
        revoked.revoke(BASE_TIME);
        sessionRepository.saveAndFlush(revoked);

        UserSession deleted = new UserSession(
                user,
                refreshTokenProvider.hash(validToken('d')),
                BASE_TIME.minusDays(1),
                BASE_TIME.plusDays(13)
        );
        ReflectionTestUtils.setField(
                deleted,
                "deletedAt",
                BASE_TIME.minusHours(1)
        );
        sessionRepository.saveAndFlush(deleted);

        entityManager.clear();

        assertFalse(sessionRepository.findById(expired.getId())
                .orElseThrow()
                .isActive(BASE_TIME));
        assertFalse(sessionRepository.findById(revoked.getId())
                .orElseThrow()
                .isActive(BASE_TIME));
        assertFalse(sessionRepository.findById(deleted.getId())
                .orElseThrow()
                .isActive(BASE_TIME));
    }

    @Test
    @DisplayName("동일 Refresh Token 해시의 비관적 락은 첫 트랜잭션 종료까지 두 번째 갱신을 대기시킨다")
    void findByRefreshTokenHashForUpdate_serializesConcurrentUpdates()
            throws Exception {
        User user = saveUser();
        String rawToken = validToken('l');
        String tokenHash = refreshTokenProvider.hash(rawToken);

        saveSession(
                user,
                tokenHash,
                BASE_TIME,
                BASE_TIME.plusDays(14)
        );

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondLocked = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(status -> {
                        sessionRepository
                                .findByRefreshTokenHashForUpdate(tokenHash)
                                .orElseThrow();
                        firstLocked.countDown();
                        await(releaseFirst);
                    })
            );

            assertTrue(firstLocked.await(3, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(status -> {
                        sessionRepository
                                .findByRefreshTokenHashForUpdate(tokenHash)
                                .orElseThrow();
                        secondLocked.countDown();
                    })
            );

            assertFalse(secondLocked.await(300, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();

            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
            assertTrue(secondLocked.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("다른 사용자 로그인은 기존 폐기와 신규 세션 저장을 한 트랜잭션으로 완료한다")
    void switchUser_revokesOldSessionAndPersistsNewSessionAtomically() {
        User previousUser = saveUser();
        User loginUser = saveUser();
        String existingToken = validToken('o');
        UserSession existing = saveSession(
                previousUser,
                refreshTokenProvider.hash(existingToken),
                BASE_TIME.minusDays(1),
                BASE_TIME.plusDays(13)
        );
        Long existingId = existing.getId();
        String newHash = refreshTokenProvider.hash(validToken('n'));

        loginSessionService.saveLoginSession(
                loginUser,
                existingToken,
                newHash,
                BASE_TIME,
                BASE_TIME.plusDays(14)
        );

        entityManager.clear();
        UserSession revoked = sessionRepository.findById(existingId)
                .orElseThrow();
        UserSession created = sessionRepository.findAll()
                .stream()
                .filter(session -> newHash.equals(
                        session.getRefreshTokenHash()
                ))
                .findFirst()
                .orElseThrow();

        assertNotNull(revoked.getRevokedAt());
        assertEquals(BASE_TIME, revoked.getRevokedAt());
        assertEquals(loginUser.getId(), created.getUser().getId());
        assertNotEquals(existingId, created.getId());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", exception);
        }
    }

    private UserSession saveSession(
            User user,
            String refreshTokenHash,
            LocalDateTime authenticatedAt,
            LocalDateTime expiresAt
    ) {
        return sessionRepository.saveAndFlush(
                new UserSession(
                        user,
                        refreshTokenHash,
                        authenticatedAt,
                        expiresAt
                )
        );
    }

    private User saveUser() {
        User saved = userRepository.saveAndFlush(
                new User(
                        "session-persistence-" + UUID.randomUUID()
                                + "@example.com",
                        passwordEncoder.encode("Password1!"),
                        "김선물",
                        LocalDate.of(2000, 1, 1)
                )
        );
        createdUserIds.add(saved.getId());
        return saved;
    }

    private String validToken(char value) {
        return Character.toString(value).repeat(43);
    }
}
