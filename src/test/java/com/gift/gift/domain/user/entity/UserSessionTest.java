package com.gift.gift.domain.user.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserSessionTest {

    private static final LocalDateTime BASE_TIME =
            LocalDateTime.of(2026, 9, 20, 12, 0);

    private User user;

    @BeforeEach
    void setUp() {
        user = new User(
                "user@example.com",
                "$2a$12$Qx1lH30rT4HaY8abG8h9xO9d6gSQ1v8"
                        + "VQqvxMDKsQFIhhnCC6Vfn2",
                "김선물",
                LocalDate.of(2000, 1, 1)
        );

        ReflectionTestUtils.setField(
                user,
                "id",
                1L
        );
    }

    @Test
    @DisplayName("신규 인증 세션은 만료 전까지 활성 상태다")
    void constructor_createsActiveSession() {
        UserSession session = newSession();

        assertTrue(
                session.isActive(
                        BASE_TIME.plusDays(1)
                )
        );
        assertTrue(session.belongsTo(1L));
    }

    @Test
    @DisplayName("만료 시각과 같거나 이후이면 세션은 비활성 상태다")
    void isActive_returnsFalseWhenExpired() {
        UserSession session = newSession();

        assertFalse(
                session.isActive(
                        BASE_TIME.plusDays(14)
                )
        );
    }

    @Test
    @DisplayName("폐기된 세션은 만료 전이어도 비활성 상태다")
    void revoke_makesSessionInactive() {
        UserSession session = newSession();

        assertTrue(
                session.revoke(
                        BASE_TIME.plusHours(1)
                )
        );

        assertFalse(
                session.isActive(
                        BASE_TIME.plusHours(2)
                )
        );
    }

    @Test
    @DisplayName("동일 사용자 재로그인은 토큰과 인증·만료 시각을 갱신한다")
    void reauthenticate_updatesAuthenticationState() {
        UserSession session = newSession();

        LocalDateTime reauthenticatedAt =
                BASE_TIME.plusDays(1);

        String newHash = "b".repeat(64);

        session.reauthenticate(
                newHash,
                reauthenticatedAt,
                reauthenticatedAt.plusDays(14),
                reauthenticatedAt
        );

        assertEquals(
                newHash,
                session.getRefreshTokenHash()
        );
        assertEquals(
                reauthenticatedAt,
                session.getAuthenticatedAt()
        );
        assertEquals(
                reauthenticatedAt.plusDays(14),
                session.getExpiresAt()
        );
    }

    @Test
    @DisplayName("만료된 세션은 재인증할 수 없다")
    void reauthenticate_rejectsExpiredSession() {
        UserSession session = newSession();

        assertThrows(
                IllegalStateException.class,
                () -> session.reauthenticate(
                        "b".repeat(64),
                        BASE_TIME.plusDays(14),
                        BASE_TIME.plusDays(28),
                        BASE_TIME.plusDays(14)
                )
        );
    }

    private UserSession newSession() {
        return new UserSession(
                user,
                "a".repeat(64),
                BASE_TIME,
                BASE_TIME.plusDays(14)
        );
    }
}
