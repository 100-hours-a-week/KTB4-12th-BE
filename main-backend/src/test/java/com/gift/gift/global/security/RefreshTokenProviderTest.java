package com.gift.gift.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RefreshTokenProviderTest {

    private final RefreshTokenProvider provider =
            new RefreshTokenProvider();

    @Test
    @DisplayName("Refresh Token은 Base64URL 무패딩 43자로 생성한다")
    void generate_createsBase64UrlToken() {
        String token = provider.generate();

        assertEquals(43, token.length());
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
    }

    @Test
    @DisplayName("같은 토큰의 해시는 동일한 64자 hex 값이다")
    void hash_isDeterministicHexValue() {
        String token = provider.generate();
        String hash = provider.hash(token);

        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertEquals(hash, provider.hash(token));
        assertNotEquals(token, hash);
    }

    @Test
    @DisplayName("생성 호출마다 새로운 난수 토큰을 반환한다")
    void generate_returnsDifferentTokens() {
        assertNotEquals(
                provider.generate(),
                provider.generate()
        );
    }
}
