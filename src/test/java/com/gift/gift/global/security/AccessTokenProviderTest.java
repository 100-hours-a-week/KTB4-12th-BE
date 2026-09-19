package com.gift.gift.global.security;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

import com.gift.gift.global.config.JwtConfig;

import static org.junit.jupiter.api.Assertions.*;

class AccessTokenProviderTest {

    private final Instant now =
            Instant.parse("2026-09-18T00:00:00Z");

    private JwtProperties properties;
    private Clock clock;
    private JwtEncoder encoder;
    private JwtDecoder decoder;
    private AccessTokenProvider provider;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties(
                Base64.getEncoder()
                        .encodeToString(new byte[32]),
                "test-issuer"
        );

        clock = Clock.fixed(now, ZoneOffset.UTC);

        JwtConfig config = new JwtConfig();
        SecretKey key = config.jwtSigningKey(properties);

        encoder = config.jwtEncoder(key);
        decoder = config.jwtDecoder(key, properties, clock);
        provider = new AccessTokenProvider(
                encoder,
                properties,
                clock
        );
    }

    @Test
    @DisplayName("발급 토큰은 회원 ID와 1시간 만료 정보를 포함한다")
    void issue_containsUserIdAndOneHourExpiration() {
        IssuedAccessToken issued = provider.issue(1L);
        Jwt jwt = decoder.decode(issued.value());

        assertEquals("1", jwt.getSubject());
        assertEquals("test-issuer", jwt.getIssuer().toString());
        assertEquals(now, jwt.getIssuedAt());
        assertEquals(now.plusSeconds(3600), jwt.getExpiresAt());

        assertEquals(
                java.util.Set.of("sub", "iat", "exp", "iss"),
                jwt.getClaims().keySet()
        );
    }

    @Test
    @DisplayName("만료 시각에 도달한 토큰은 거부한다")
    void decode_rejectsExpiredToken() {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("1")
                .issuer(properties.issuer())
                .issuedAt(now.minusSeconds(3600))
                .expiresAt(now)
                .build();

        assertThrows(
                JwtException.class,
                () -> decoder.decode(encode(claims))
        );
    }

    @Test
    @DisplayName("다른 서명키로 발급한 토큰은 거부한다")
    void decode_rejectsDifferentSigningKey() {
        byte[] otherBytes = new byte[32];
        otherBytes[0] = 1;

        JwtProperties otherProperties = new JwtProperties(
                Base64.getEncoder()
                        .encodeToString(otherBytes),
                properties.issuer()
        );

        JwtConfig config = new JwtConfig();

        AccessTokenProvider otherProvider =
                new AccessTokenProvider(
                        config.jwtEncoder(
                                config.jwtSigningKey(otherProperties)
                        ),
                        otherProperties,
                        clock
                );

        assertThrows(
                JwtException.class,
                () -> decoder.decode(
                        otherProvider.issue(1L).value()
                )
        );
    }

    @Test
    @DisplayName("발급자 불일치 토큰은 거부한다")
    void decode_rejectsDifferentIssuer() {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("1")
                .issuer("other-issuer")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();

        assertThrows(
                JwtException.class,
                () -> decoder.decode(encode(claims))
        );
    }

    @Test
    @DisplayName("발급 시각이 없는 토큰은 거부한다")
    void decode_rejectsMissingIssuedAt() {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("1")
                .issuer(properties.issuer())
                .expiresAt(now.plusSeconds(3600))
                .build();

        assertThrows(
                JwtException.class,
                () -> decoder.decode(encode(claims))
        );
    }

    @Test
    @DisplayName("회원 ID가 아닌 subject는 거부한다")
    void decode_rejectsInvalidSubject() {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("not-user-id")
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();

        assertThrows(
                JwtException.class,
                () -> decoder.decode(encode(claims))
        );
    }

    private String encode(JwtClaimsSet claims) {
        return encoder.encode(
                JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        claims
                )
        ).getTokenValue();
    }
}
