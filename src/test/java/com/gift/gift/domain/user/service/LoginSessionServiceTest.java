package com.gift.gift.domain.user.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.auth.entity.UserSession;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.domain.auth.repository.UserSessionRepository;
import com.gift.gift.domain.auth.service.LoginSessionService;
import com.gift.gift.global.security.RefreshTokenProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=update")
@Import(LoginSessionServiceTest.FixedClockConfig.class)
class LoginSessionServiceTest {

    private static final LocalDateTime LOGIN_TIME =
            LocalDateTime.of(2026, 9, 21, 12, 0);

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            ZoneId zoneId = ZoneId.of("Asia/Seoul");

            return Clock.fixed(
                    LOGIN_TIME.atZone(zoneId).toInstant(),
                    zoneId
            );
        }
    }

    private final List<Long> createdUserIds = new ArrayList<>();

    @Autowired
    private LoginSessionService loginSessionService;

    @MockitoSpyBean
    private UserSessionRepository sessionRepository;

    @MockitoSpyBean
    private RefreshTokenProvider refreshTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManager entityManager;

    @AfterEach
    void cleanUp() {
        sessionRepository.deleteAll();
        createdUserIds.forEach(userRepository::deleteById);
        createdUserIds.clear();
    }

    @Test
    @DisplayName("Refresh Cookie가 없으면 기존 세션을 조회하지 않고 새 세션을 생성한다")
    void saveLoginSession_createsSessionWhenCookieIsMissing() {
        User user = saveUser();
        String newHash = refreshTokenProvider.hash(validToken('n'));
        clearInvocations(refreshTokenProvider, sessionRepository);

        loginSessionService.saveLoginSession(
                user,
                null,
                newHash,
                LOGIN_TIME,
                LOGIN_TIME.plusDays(14)
        );

        assertCreatedSession(user, newHash);
        verify(refreshTokenProvider, never()).hash(any());
        verify(sessionRepository, never())
                .findByRefreshTokenHashForUpdate(any());
    }

    @Test
    @DisplayName("Cookie 형식이 잘못되면 해시 조회 없이 새 세션을 생성한다")
    void saveLoginSession_createsSessionWithoutLookupForMalformedCookie() {
        User user = saveUser();
        String newHash = refreshTokenProvider.hash(validToken('n'));
        clearInvocations(refreshTokenProvider, sessionRepository);

        loginSessionService.saveLoginSession(
                user,
                "invalid-cookie",
                newHash,
                LOGIN_TIME,
                LOGIN_TIME.plusDays(14)
        );

        assertCreatedSession(user, newHash);
        verify(refreshTokenProvider, never()).hash(any());
        verify(sessionRepository, never())
                .findByRefreshTokenHashForUpdate(any());
    }

    @Test
    @DisplayName("Cookie 해시와 일치하는 세션이 없으면 새 세션을 생성한다")
    void saveLoginSession_createsSessionWhenCookieHasNoMatchingSession() {
        User user = saveUser();
        String existingToken = validToken('e');
        String newHash = refreshTokenProvider.hash(validToken('n'));

        loginSessionService.saveLoginSession(
                user,
                existingToken,
                newHash,
                LOGIN_TIME,
                LOGIN_TIME.plusDays(14)
        );

        assertCreatedSession(user, newHash);
        verify(sessionRepository)
                .findByRefreshTokenHashForUpdate(
                        refreshTokenProvider.hash(existingToken)
                );
    }

    @Test
    @DisplayName("동일 사용자의 활성 세션은 ID를 유지하고 인증 상태를 갱신한다")
    void saveLoginSession_reusesActiveSessionForSameUser() {
        User user = saveUser();
        String existingToken = validToken('e');
        UserSession existing = saveSession(
                user,
                existingToken,
                LOGIN_TIME.minusDays(1),
                LOGIN_TIME.plusDays(13)
        );
        Long existingId = existing.getId();
        String newHash = refreshTokenProvider.hash(validToken('n'));

        loginSessionService.saveLoginSession(
                user,
                existingToken,
                newHash,
                LOGIN_TIME,
                LOGIN_TIME.plusDays(14)
        );

        entityManager.clear();
        UserSession updated = sessionRepository.findById(existingId)
                .orElseThrow();

        assertEquals(1, sessionRepository.count());
        assertEquals(existingId, updated.getId());
        assertEquals(newHash, updated.getRefreshTokenHash());
        assertEquals(LOGIN_TIME, updated.getAuthenticatedAt());
        assertEquals(LOGIN_TIME.plusDays(14), updated.getExpiresAt());
        assertNull(updated.getRevokedAt());
    }

    @Test
    @DisplayName("다른 사용자의 활성 세션은 폐기하고 로그인 사용자의 새 세션을 생성한다")
    void saveLoginSession_revokesPreviousUserAndCreatesNewSession() {
        User previousUser = saveUser();
        User loginUser = saveUser();
        String existingToken = validToken('e');
        UserSession existing = saveSession(
                previousUser,
                existingToken,
                LOGIN_TIME.minusDays(1),
                LOGIN_TIME.plusDays(13)
        );
        Long existingId = existing.getId();
        String newHash = refreshTokenProvider.hash(validToken('n'));

        loginSessionService.saveLoginSession(
                loginUser,
                existingToken,
                newHash,
                LOGIN_TIME,
                LOGIN_TIME.plusDays(14)
        );

        entityManager.clear();
        UserSession revoked = sessionRepository.findById(existingId)
                .orElseThrow();
        UserSession created = findByRefreshTokenHash(newHash);

        assertEquals(LOGIN_TIME, revoked.getRevokedAt());
        assertNotEquals(existingId, created.getId());
        assertEquals(loginUser.getId(), created.getUser().getId());
    }

    @Test
    @DisplayName("만료된 세션은 재활성화하지 않고 새 세션을 생성한다")
    void saveLoginSession_createsNewSessionForExpiredSession() {
        User user = saveUser();
        String existingToken = validToken('e');
        UserSession expired = saveSession(
                user,
                existingToken,
                LOGIN_TIME.minusDays(15),
                LOGIN_TIME.minusSeconds(1)
        );
        Long expiredId = expired.getId();
        String newHash = refreshTokenProvider.hash(validToken('n'));

        loginSessionService.saveLoginSession(
                user,
                existingToken,
                newHash,
                LOGIN_TIME,
                LOGIN_TIME.plusDays(14)
        );

        entityManager.clear();
        assertEquals(2, sessionRepository.count());
        assertEquals(
                refreshTokenProvider.hash(existingToken),
                sessionRepository.findById(expiredId)
                        .orElseThrow()
                        .getRefreshTokenHash()
        );
        assertEquals(newHash, findByRefreshTokenHash(newHash)
                .getRefreshTokenHash());
    }

    @Test
    @DisplayName("폐기된 세션은 재활성화하지 않고 새 세션을 생성한다")
    void saveLoginSession_createsNewSessionForRevokedSession() {
        User user = saveUser();
        String existingToken = validToken('e');
        UserSession revoked = new UserSession(
                user,
                refreshTokenProvider.hash(existingToken),
                LOGIN_TIME.minusDays(1),
                LOGIN_TIME.plusDays(13)
        );
        revoked.revoke(LOGIN_TIME);
        sessionRepository.saveAndFlush(revoked);
        Long revokedId = revoked.getId();
        String newHash = refreshTokenProvider.hash(validToken('n'));

        loginSessionService.saveLoginSession(
                user,
                existingToken,
                newHash,
                LOGIN_TIME,
                LOGIN_TIME.plusDays(14)
        );

        entityManager.clear();
        assertEquals(2, sessionRepository.count());
        assertNotNull(sessionRepository.findById(revokedId)
                .orElseThrow()
                .getRevokedAt());
        assertEquals(newHash, findByRefreshTokenHash(newHash)
                .getRefreshTokenHash());
    }

    @Test
    @DisplayName("새 세션 저장 실패는 예외를 전파하고 기존 세션 폐기를 롤백한다")
    void saveLoginSession_rollsBackRevocationWhenNewSessionSaveFails() {
        User previousUser = saveUser();
        User loginUser = saveUser();
        String existingToken = validToken('e');
        UserSession existing = saveSession(
                previousUser,
                existingToken,
                LOGIN_TIME.minusDays(1),
                LOGIN_TIME.plusDays(13)
        );
        Long existingId = existing.getId();
        String newHash = refreshTokenProvider.hash(validToken('n'));

        doThrow(new DataAccessResourceFailureException("save failed"))
                .when(sessionRepository)
                .saveAndFlush(any(UserSession.class));

        assertThrows(
                DataAccessResourceFailureException.class,
                () -> loginSessionService.saveLoginSession(
                        loginUser,
                        existingToken,
                        newHash,
                        LOGIN_TIME,
                        LOGIN_TIME.plusDays(14)
                )
        );

        entityManager.clear();
        UserSession rolledBack = sessionRepository.findById(existingId)
                .orElseThrow();

        assertNull(rolledBack.getRevokedAt());
        assertEquals(1, sessionRepository.count());
    }

    private void assertCreatedSession(
            User user,
            String refreshTokenHash
    ) {
        entityManager.clear();
        UserSession created = findByRefreshTokenHash(
                refreshTokenHash
        );

        assertEquals(user.getId(), created.getUser().getId());
        assertEquals(LOGIN_TIME, created.getAuthenticatedAt());
        assertEquals(LOGIN_TIME.plusDays(14), created.getExpiresAt());
        assertNull(created.getRevokedAt());
    }

    private UserSession saveSession(
            User user,
            String rawToken,
            LocalDateTime authenticatedAt,
            LocalDateTime expiresAt
    ) {
        return sessionRepository.saveAndFlush(
                new UserSession(
                        user,
                        refreshTokenProvider.hash(rawToken),
                        authenticatedAt,
                        expiresAt
                )
        );
    }

    private UserSession findByRefreshTokenHash(
            String refreshTokenHash
    ) {
        return sessionRepository.findAll()
                .stream()
                .filter(session -> refreshTokenHash.equals(
                        session.getRefreshTokenHash()
                ))
                .findFirst()
                .orElseThrow();
    }

    private User saveUser() {
        User saved = userRepository.saveAndFlush(
                new User(
                        "login-session-" + UUID.randomUUID()
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
