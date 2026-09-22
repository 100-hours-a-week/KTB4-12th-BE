package com.gift.gift.domain.user.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.auth.entity.UserSession;
import com.gift.gift.domain.auth.repository.UserSessionRepository;
import com.gift.gift.domain.auth.service.LogoutService;
import com.gift.gift.global.security.RefreshTokenProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogoutServiceTest {

    private static final String REFRESH_TOKEN = "a".repeat(43);
    private static final String REFRESH_TOKEN_HASH = "b".repeat(64);

    private static final Instant NOW_INSTANT =
            Instant.parse("2026-09-21T03:00:00Z");

    private static final ZoneId ZONE_ID =
            ZoneId.of("Asia/Seoul");

    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(NOW_INSTANT, ZONE_ID);

    @Mock
    private UserSessionRepository sessionRepository;

    @Mock
    private RefreshTokenProvider refreshTokenProvider;

    private LogoutService logoutService;
    private User user;

    @BeforeEach
    void setUp() {
        logoutService = new LogoutService(
                sessionRepository,
                refreshTokenProvider,
                Clock.fixed(NOW_INSTANT, ZONE_ID)
        );

        user = new User(
                "user@example.com",
                "$2a$12$Qx1lH30rT4HaY8abG8h9xO9d6gSQ1v8"
                        + "VQqvxMDKsQFIhhnCC6Vfn2",
                "김선물",
                LocalDate.of(2000, 1, 1)
        );
    }

    @Test
    @DisplayName("Cookie가 없으면 세션 조회 없이 멱등 성공한다")
    void logout_isIdempotentWhenCookieIsMissing() {
        when(refreshTokenProvider.isValidFormat(null))
                .thenReturn(false);

        logoutService.logout(null);

        verify(refreshTokenProvider, never()).hash(null);
        verify(sessionRepository, never())
                .findByRefreshTokenHashForUpdate(REFRESH_TOKEN_HASH);
        verify(sessionRepository, never()).flush();
    }

    @Test
    @DisplayName("Cookie 형식이 잘못되면 세션 조회 없이 멱등 성공한다")
    void logout_isIdempotentWhenCookieIsMalformed() {
        when(refreshTokenProvider.isValidFormat("invalid"))
                .thenReturn(false);

        logoutService.logout("invalid");

        verify(refreshTokenProvider, never()).hash("invalid");
        verify(sessionRepository, never())
                .findByRefreshTokenHashForUpdate(REFRESH_TOKEN_HASH);
        verify(sessionRepository, never()).flush();
    }

    @Test
    @DisplayName("일치하는 세션이 없으면 멱등 성공한다")
    void logout_isIdempotentWhenSessionDoesNotExist() {
        stubToken();
        when(sessionRepository
                .findByRefreshTokenHashForUpdate(REFRESH_TOKEN_HASH))
                .thenReturn(Optional.empty());

        logoutService.logout(REFRESH_TOKEN);

        verify(sessionRepository, never()).flush();
    }

    @Test
    @DisplayName("활성 세션은 현재 시각으로 폐기하고 flush한다")
    void logout_revokesActiveSession() {
        UserSession session = activeSession();
        stubSession(session);

        logoutService.logout(REFRESH_TOKEN);

        assertEquals(NOW, session.getRevokedAt());
        verify(sessionRepository).flush();
    }

    @Test
    @DisplayName("이미 폐기된 세션은 폐기 시각을 덮어쓰지 않는다")
    void logout_doesNotOverwriteRevokedSession() {
        LocalDateTime originalRevokedAt = NOW.minusHours(1);
        UserSession session = activeSession();
        session.revoke(originalRevokedAt);
        stubSession(session);

        logoutService.logout(REFRESH_TOKEN);

        assertEquals(originalRevokedAt, session.getRevokedAt());
        verify(sessionRepository, never()).flush();
    }

    @Test
    @DisplayName("삭제된 세션은 폐기 상태를 변경하지 않는다")
    void logout_doesNotChangeDeletedSession() {
        UserSession session = activeSession();
        ReflectionTestUtils.setField(
                session,
                "deletedAt",
                NOW.minusHours(1)
        );
        stubSession(session);

        logoutService.logout(REFRESH_TOKEN);

        assertNull(session.getRevokedAt());
        verify(sessionRepository, never()).flush();
    }

    @Test
    @DisplayName("만료된 세션도 조회되면 폐기한다")
    void logout_revokesExpiredSession() {
        UserSession session = new UserSession(
                user,
                REFRESH_TOKEN_HASH,
                NOW.minusDays(15),
                NOW.minusSeconds(1)
        );
        stubSession(session);

        logoutService.logout(REFRESH_TOKEN);

        assertEquals(NOW, session.getRevokedAt());
        verify(sessionRepository).flush();
    }

    private void stubSession(UserSession session) {
        stubToken();
        when(sessionRepository
                .findByRefreshTokenHashForUpdate(REFRESH_TOKEN_HASH))
                .thenReturn(Optional.of(session));
    }

    private void stubToken() {
        when(refreshTokenProvider.isValidFormat(REFRESH_TOKEN))
                .thenReturn(true);
        when(refreshTokenProvider.hash(REFRESH_TOKEN))
                .thenReturn(REFRESH_TOKEN_HASH);
    }

    private UserSession activeSession() {
        return new UserSession(
                user,
                REFRESH_TOKEN_HASH,
                NOW.minusDays(1),
                NOW.plusDays(13)
        );
    }
}
