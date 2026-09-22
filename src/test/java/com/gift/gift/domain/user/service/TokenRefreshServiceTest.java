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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.auth.entity.UserSession;
import com.gift.gift.domain.auth.repository.UserSessionRepository;
import com.gift.gift.domain.auth.service.TokenRefreshService;
import com.gift.gift.domain.auth.support.TokenRefreshResult;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.security.AccessTokenProvider;
import com.gift.gift.global.security.IssuedAccessToken;
import com.gift.gift.global.security.RefreshTokenProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenRefreshServiceTest {

    private static final String CURRENT_TOKEN = "a".repeat(43);
    private static final String CURRENT_HASH = "b".repeat(64);
    private static final String NEW_TOKEN = "c".repeat(43);
    private static final String NEW_HASH = "d".repeat(64);
    private static final String ACCESS_TOKEN = "access-token";

    private static final Instant NOW_INSTANT =
            Instant.parse("2026-09-21T03:00:00Z");

    private static final ZoneId ZONE_ID =
            ZoneId.of("Asia/Seoul");

    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(NOW_INSTANT, ZONE_ID);

    @Mock
    private UserSessionRepository sessionRepository;

    @Mock
    private AccessTokenProvider accessTokenProvider;

    @Mock
    private RefreshTokenProvider refreshTokenProvider;

    private TokenRefreshService tokenRefreshService;
    private User user;

    @BeforeEach
    void setUp() {
        tokenRefreshService = new TokenRefreshService(
                sessionRepository,
                accessTokenProvider,
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

        ReflectionTestUtils.setField(user, "id", 1L);
    }

    @Test
    @DisplayName("유효한 Refresh Token은 Access Token을 발급하고 세션 해시만 회전한다")
    void refresh_rotatesRefreshTokenAndPreservesAuthenticationWindow() {
        UserSession session = activeSession();
        LocalDateTime authenticatedAt = session.getAuthenticatedAt();
        LocalDateTime expiresAt = session.getExpiresAt();
        stubSuccessfulRefresh(session);

        TokenRefreshResult result =
                tokenRefreshService.refresh(CURRENT_TOKEN);

        assertEquals(ACCESS_TOKEN, result.accessToken());
        assertEquals(3600, result.expiresIn());
        assertEquals(NEW_TOKEN, result.refreshToken());
        assertEquals(NEW_HASH, session.getRefreshTokenHash());
        assertEquals(authenticatedAt, session.getAuthenticatedAt());
        assertEquals(expiresAt, session.getExpiresAt());

        verify(sessionRepository).flush();
    }

    @Test
    @DisplayName("Cookie가 없거나 형식이 잘못되면 해시 조회 없이 401을 반환한다")
    void refresh_rejectsMalformedTokenBeforeHashing() {
        when(refreshTokenProvider.isValidFormat(null))
                .thenReturn(false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> tokenRefreshService.refresh(null)
        );

        assertEquals(
                ErrorCode.INVALID_REFRESH_TOKEN,
                exception.getErrorCode()
        );
        verify(refreshTokenProvider, never()).hash(null);
        verify(sessionRepository, never())
                .findByRefreshTokenHashForUpdate(CURRENT_HASH);
    }

    @Test
    @DisplayName("토큰 해시와 일치하는 세션이 없으면 401을 반환한다")
    void refresh_rejectsUnknownToken() {
        stubCurrentToken();
        when(sessionRepository
                .findByRefreshTokenHashForUpdate(CURRENT_HASH))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> tokenRefreshService.refresh(CURRENT_TOKEN)
        );

        assertEquals(
                ErrorCode.INVALID_REFRESH_TOKEN,
                exception.getErrorCode()
        );
        verify(accessTokenProvider, never()).issue(1L);
    }

    @Test
    @DisplayName("만료된 세션은 토큰을 회전하지 않고 401을 반환한다")
    void refresh_rejectsExpiredSession() {
        UserSession expired = new UserSession(
                user,
                CURRENT_HASH,
                NOW.minusDays(14),
                NOW.minusSeconds(1)
        );
        stubSession(expired);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> tokenRefreshService.refresh(CURRENT_TOKEN)
        );

        assertEquals(
                ErrorCode.INVALID_REFRESH_TOKEN,
                exception.getErrorCode()
        );
        assertEquals(CURRENT_HASH, expired.getRefreshTokenHash());
        verify(sessionRepository, never()).flush();
    }

    @Test
    @DisplayName("폐기된 세션은 토큰을 회전하지 않고 401을 반환한다")
    void refresh_rejectsRevokedSession() {
        UserSession revoked = activeSession();
        revoked.revoke(NOW.minusMinutes(1));
        stubSession(revoked);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> tokenRefreshService.refresh(CURRENT_TOKEN)
        );

        assertEquals(
                ErrorCode.INVALID_REFRESH_TOKEN,
                exception.getErrorCode()
        );
        assertEquals(CURRENT_HASH, revoked.getRefreshTokenHash());
        verify(sessionRepository, never()).flush();
    }

    @Test
    @DisplayName("삭제된 세션은 토큰을 회전하지 않고 401을 반환한다")
    void refresh_rejectsDeletedSession() {
        UserSession deleted = activeSession();
        ReflectionTestUtils.setField(
                deleted,
                "deletedAt",
                NOW.minusMinutes(1)
        );
        stubSession(deleted);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> tokenRefreshService.refresh(CURRENT_TOKEN)
        );

        assertEquals(
                ErrorCode.INVALID_REFRESH_TOKEN,
                exception.getErrorCode()
        );
        assertEquals(CURRENT_HASH, deleted.getRefreshTokenHash());
        verify(sessionRepository, never()).flush();
    }

    @Test
    @DisplayName("삭제된 회원의 세션은 토큰을 회전하지 않고 401을 반환한다")
    void refresh_rejectsInactiveUser() {
        ReflectionTestUtils.setField(
                user,
                "deletedAt",
                NOW.minusDays(1)
        );
        UserSession session = activeSession();
        stubSession(session);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> tokenRefreshService.refresh(CURRENT_TOKEN)
        );

        assertEquals(
                ErrorCode.INVALID_REFRESH_TOKEN,
                exception.getErrorCode()
        );
        verify(sessionRepository, never()).flush();
    }

    @Test
    @DisplayName("회전이 끝난 이전 Refresh Token은 다시 사용할 수 없다")
    void refresh_rejectsPreviousTokenAfterRotation() {
        UserSession session = activeSession();
        stubCurrentToken();
        when(sessionRepository
                .findByRefreshTokenHashForUpdate(CURRENT_HASH))
                .thenReturn(Optional.of(session))
                .thenReturn(Optional.empty());
        when(accessTokenProvider.issue(1L))
                .thenReturn(new IssuedAccessToken(
                        ACCESS_TOKEN,
                        NOW_INSTANT,
                        NOW_INSTANT.plusSeconds(3600)
                ));
        when(refreshTokenProvider.generate())
                .thenReturn(NEW_TOKEN);
        when(refreshTokenProvider.hash(NEW_TOKEN))
                .thenReturn(NEW_HASH);

        tokenRefreshService.refresh(CURRENT_TOKEN);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> tokenRefreshService.refresh(CURRENT_TOKEN)
        );

        assertEquals(
                ErrorCode.INVALID_REFRESH_TOKEN,
                exception.getErrorCode()
        );
        assertEquals(NEW_HASH, session.getRefreshTokenHash());
    }

    @Test
    @DisplayName("비관적 락 획득 실패는 409 충돌로 변환한다")
    void refresh_mapsPessimisticLockFailureToConflict() {
        stubCurrentToken();
        when(sessionRepository
                .findByRefreshTokenHashForUpdate(CURRENT_HASH))
                .thenThrow(new PessimisticLockingFailureException(
                        "lock failed"
                ));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> tokenRefreshService.refresh(CURRENT_TOKEN)
        );

        assertEquals(
                ErrorCode.TOKEN_REFRESH_CONFLICT,
                exception.getErrorCode()
        );
    }

    @Test
    @DisplayName("락 쿼리 시간 초과는 409 충돌로 변환한다")
    void refresh_mapsQueryTimeoutToConflict() {
        stubCurrentToken();
        when(sessionRepository
                .findByRefreshTokenHashForUpdate(CURRENT_HASH))
                .thenThrow(new QueryTimeoutException("timed out"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> tokenRefreshService.refresh(CURRENT_TOKEN)
        );

        assertEquals(
                ErrorCode.TOKEN_REFRESH_CONFLICT,
                exception.getErrorCode()
        );
    }

    @Test
    @DisplayName("세션 flush 실패는 500 처리를 위해 저장소 예외를 그대로 전파한다")
    void refresh_propagatesUnexpectedPersistenceFailure() {
        UserSession session = activeSession();
        stubSuccessfulRefresh(session);
        doThrow(new DataAccessResourceFailureException(
                        "flush failed"
                ))
                .when(sessionRepository)
                .flush();

        assertThrows(
                DataAccessResourceFailureException.class,
                () -> tokenRefreshService.refresh(CURRENT_TOKEN)
        );
    }

    private void stubSuccessfulRefresh(UserSession session) {
        stubSession(session);

        when(accessTokenProvider.issue(1L))
                .thenReturn(new IssuedAccessToken(
                        ACCESS_TOKEN,
                        NOW_INSTANT,
                        NOW_INSTANT.plusSeconds(3600)
                ));

        when(refreshTokenProvider.generate())
                .thenReturn(NEW_TOKEN);

        when(refreshTokenProvider.hash(NEW_TOKEN))
                .thenReturn(NEW_HASH);
    }

    private void stubSession(UserSession session) {
        stubCurrentToken();
        when(sessionRepository
                .findByRefreshTokenHashForUpdate(CURRENT_HASH))
                .thenReturn(Optional.of(session));
    }

    private void stubCurrentToken() {
        when(refreshTokenProvider.isValidFormat(CURRENT_TOKEN))
                .thenReturn(true);
        when(refreshTokenProvider.hash(CURRENT_TOKEN))
                .thenReturn(CURRENT_HASH);
    }

    private UserSession activeSession() {
        return new UserSession(
                user,
                CURRENT_HASH,
                NOW.minusDays(1),
                NOW.plusDays(13)
        );
    }
}
