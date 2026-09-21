package com.gift.gift.global.security;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshCookieProviderTest {

    private final RefreshCookieProvider provider =
            new RefreshCookieProvider();

    @Test
    @DisplayName("발급 Cookie는 14일 수명과 보안 속성을 적용한다")
    void create_appliesRefreshCookieSecurityAttributes() {
        String token = "a".repeat(43);

        ResponseCookie cookie = provider.create(token);

        assertEquals(RefreshCookieProvider.COOKIE_NAME, cookie.getName());
        assertEquals(token, cookie.getValue());
        assertEquals(Duration.ofDays(14), cookie.getMaxAge());
        assertEquals("/auth", cookie.getPath());
        assertEquals("Lax", cookie.getSameSite());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        assertFalse(cookie.toString().contains("Domain="));
    }

    @Test
    @DisplayName("만료 Cookie는 같은 경로와 보안 속성으로 Max-Age를 0으로 설정한다")
    void expire_clearsRefreshCookieWithSameScope() {
        ResponseCookie cookie = provider.expire();

        assertEquals(RefreshCookieProvider.COOKIE_NAME, cookie.getName());
        assertEquals("", cookie.getValue());
        assertEquals(Duration.ZERO, cookie.getMaxAge());
        assertEquals("/auth", cookie.getPath());
        assertEquals("Lax", cookie.getSameSite());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
    }
}
