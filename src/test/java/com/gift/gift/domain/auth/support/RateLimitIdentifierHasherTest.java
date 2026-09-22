package com.gift.gift.domain.auth.support;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.auth.support.RateLimitIdentifierHasher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitIdentifierHasherTest {

    private RateLimitIdentifierHasher hasher;

    @BeforeEach
    void setUp() {
        byte[] keyBytes = new byte[32];

        for (int index = 0; index < keyBytes.length; index++) {
            keyBytes[index] = (byte) (index + 1);
        }

        SecretKey secretKey = new SecretKeySpec(
                keyBytes,
                "HmacSHA256"
        );

        hasher = new RateLimitIdentifierHasher(
                secretKey
        );
    }

    @Test
    @DisplayName("동일한 IP는 항상 동일한 HMAC 식별자로 변환한다")
    void hashIp_returnsSameHashForSameIp() {
        String first = hasher.hashIp("203.0.113.10");
        String second = hasher.hashIp("203.0.113.10");

        assertEquals(first, second);
    }

    @Test
    @DisplayName("IP 앞뒤 공백을 제거한 후 HMAC 식별자를 생성한다")
    void hashIp_trimsIpBeforeHashing() {
        String normalized =
                hasher.hashIp("203.0.113.10");

        String withWhitespace =
                hasher.hashIp("  203.0.113.10  ");

        assertEquals(normalized, withWhitespace);
    }

    @Test
    @DisplayName("정규화 결과가 동일한 이메일은 같은 HMAC 식별자로 변환한다")
    void hashEmail_normalizesEmailBeforeHashing() {
        String normalized =
                hasher.hashEmail("user@example.com");

        String unnormalized =
                hasher.hashEmail("  USER@Example.COM  ");

        assertEquals(normalized, unnormalized);
    }

    @Test
    @DisplayName("동일 문자열도 IP와 이메일 식별자는 서로 다르게 생성한다")
    void hash_usesDifferentDomainsForIpAndEmail() {
        String ipHash = hasher.hashIp("same-value");
        String emailHash = hasher.hashEmail("same-value");

        assertNotEquals(ipHash, emailHash);
    }

    @Test
    @DisplayName("HMAC 식별자는 64자의 소문자 16진수 문자열이다")
    void hash_returnsLowercaseHexString() {
        String result =
                hasher.hashEmail("user@example.com");

        assertEquals(64, result.length());
        assertTrue(result.matches("^[0-9a-f]{64}$"));
    }
}
