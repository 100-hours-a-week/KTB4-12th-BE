package com.gift.gift.domain.recommendation.chat.security;

import java.time.*;
import java.util.*;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import com.gift.gift.domain.recommendation.chat.config.ChatConversationTokenProperties;
import com.gift.gift.domain.recommendation.chat.model.ChatConversationIdentity;
import com.gift.gift.global.config.JwtConfig;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.security.AccessTokenProvider;
import com.gift.gift.global.security.JwtProperties;

import static org.junit.jupiter.api.Assertions.*;

class ChatConversationTokenProviderTest {

    private static final Instant NOW =
            Instant.parse("2026-10-10T00:00:00Z");

    private static final UUID ID =
            UUID.fromString("df68e1e3-6c35-4cda-8250-1092cf78ea23");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final JwtProperties login = new JwtProperties(
            base64(0),
            "https://login.example"
    );

    private final ChatConversationTokenProperties properties =
            new ChatConversationTokenProperties(
                    base64(1),
                    "https://chat.example"
            );

    private final ChatConversationTokenProvider provider =
            new ChatConversationTokenProvider(
                    properties,
                    login,
                    clock
            );

    private final ChatConversationIdentity identity =
            new ChatConversationIdentity(ID, 1L, 2L);

    @Test
    @DisplayName("정상 토큰은 동일 발신자와 대화에서만 통과하고 만료 정보를 반환한다")
    void issueAndVerify_succeedForOwner() {
        var token = provider.issue(identity, NOW.plusSeconds(3600));

        assertEquals(
                identity,
                provider.verify(token.value(), 1L, ID)
        );

        String payload = new String(
                Base64.getUrlDecoder().decode(token.value().split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8
        );
        assertFalse(payload.contains("\"tokenType\""));
        assertTrue(payload.contains(ChatConversationTokenProvider.AUDIENCE));

        assertEquals(NOW, token.issuedAt());
        assertEquals(NOW.plusSeconds(3600), token.expiresAt());

        assertFalse(token.toString().contains(token.value()));
        assertFalse(
                properties.toString().contains(properties.secretBase64())
        );
    }

    @Test
    @DisplayName("대화 토큰은 별도 기간 계산 없이 전달받은 로그인 만료 시각을 사용한다")
    void issue_usesLoginAccessTokenExpiration() {
        Instant loginExpiration = NOW.plusSeconds(137);
        var issued = provider.issue(identity, loginExpiration);
        assertEquals(loginExpiration, issued.expiresAt());
        assertEquals(identity, provider.verify(issued.value(), 1L, ID));
        var expired = new ChatConversationTokenProvider(
                properties, login, Clock.fixed(loginExpiration, ZoneOffset.UTC)
        );
        assertNotFound(() -> expired.verify(issued.value(), 1L, ID));
    }

    @Test
    @DisplayName("로그인 만료 시각이 없거나 이미 만료된 경우 대화 토큰을 발급하지 않는다")
    void issue_rejectsMissingOrExpiredLoginExpiration() {
        for (Instant expiration : Arrays.asList(null, NOW, NOW.minusSeconds(1))) {
            assertEquals(ErrorCode.UNAUTHORIZED, assertThrows(
                    BusinessException.class, () -> provider.issue(identity, expiration)
            ).getErrorCode());
        }
    }

    @Test
    @DisplayName("다른 발신자와 다른 대화 요청은 404로 차단한다")
    void verify_rejectsOtherOwnerAndConversation() {
        String token = provider.issue(identity, NOW.plusSeconds(3600)).value();

        assertNotFound(
                () -> provider.verify(token, 3L, ID)
        );

        assertNotFound(
                () -> provider.verify(token, 1L, UUID.randomUUID())
        );
    }

    @Test
    @DisplayName("누락·공백·손상·서명 변조 토큰은 404로 차단한다")
    void verify_rejectsMissingAndTamperedToken() {
        String token = provider.issue(identity, NOW.plusSeconds(3600)).value();

        String[] parts = token.split("\\.");
        String changed = parts[2].charAt(0) == 'A' ? "B" : "A";

        String tampered = parts[0] + "."
                + parts[1] + "."
                + changed + parts[2].substring(1);

        for (String value : Arrays.asList(
                null,
                "",
                " ",
                "broken",
                tampered
        )) {
            assertNotFound(
                    () -> provider.verify(value, 1L, ID)
            );
        }
    }

    @Test
    @DisplayName("만료 직전은 허용하고 만료 시각부터 거절한다")
    void verify_checksExactExpiryBoundary() {
        String value = provider.issue(identity, NOW.plusSeconds(3600)).value();

        var before = new ChatConversationTokenProvider(
                properties,
                login,
                Clock.fixed(
                        NOW.plusSeconds(3599),
                        ZoneOffset.UTC
                )
        );

        assertEquals(
                identity,
                before.verify(value, 1L, ID)
        );

        var expired = new ChatConversationTokenProvider(
                properties,
                login,
                Clock.fixed(
                        NOW.plusSeconds(3600),
                        ZoneOffset.UTC
                )
        );

        assertNotFound(
                () -> expired.verify(value, 1L, ID)
        );
    }

    @Test
    @DisplayName("서명은 맞아도 발급자·용도·시간·ID claim이 잘못되면 거절한다")
    void verify_rejectsInvalidSignedClaims() {
        Map<String, Object> valid = new LinkedHashMap<>();

        valid.put("iss", properties.issuer());
        valid.put(
                "aud",
                List.of(ChatConversationTokenProvider.AUDIENCE)
        );
        valid.put("sub", ID.toString());
        valid.put("iat", NOW);
        valid.put("exp", NOW.plusSeconds(3600));
        valid.put("conversationId", ID.toString());
        valid.put("senderUserId", 1L);
        valid.put("recipientUserId", 2L);

        for (var replacement : List.of(
                Map.entry(
                        "iss",
                        (Object) "https://other.example"
                ),
                Map.entry(
                        "aud",
                        (Object) List.of("login")
                ),
                Map.entry(
                        "sub",
                        (Object) "1"
                ),
                Map.entry(
                        "conversationId",
                        (Object) "not-uuid"
                ),
                Map.entry(
                        "senderUserId",
                        (Object) "1"
                ),
                Map.entry(
                        "senderUserId",
                        (Object) 1.5
                ),
                Map.entry(
                        "recipientUserId",
                        (Object) true
                ),
                Map.entry(
                        "recipientUserId",
                        (Object) 0L
                ),
                Map.entry(
                        "recipientUserId",
                        (Object) 9_007_199_254_740_992L
                ),
                Map.entry(
                        "iat",
                        (Object) NOW.plusSeconds(1)
                ),
                Map.entry(
                        "exp",
                        (Object) NOW
                )
        )) {
            var claims = new LinkedHashMap<>(valid);
            claims.put(
                    replacement.getKey(),
                    replacement.getValue()
            );

            if ("exp".equals(replacement.getKey())) {
                claims.put("iat", NOW.minusSeconds(1));
            }

            assertNotFound(
                    () -> provider.verify(sign(claims), 1L, ID)
            );
        }

        for (String name : valid.keySet()) {
            var claims = new LinkedHashMap<>(valid);
            claims.remove(name);

            assertNotFound(
                    () -> provider.verify(sign(claims), 1L, ID)
            );
        }
    }

    @Test
    @DisplayName("다른 대화 서명키의 토큰은 거부한다")
    void verify_rejectsOtherSigningKey() {
        var other = new ChatConversationTokenProvider(
                new ChatConversationTokenProperties(
                        base64(2),
                        properties.issuer()
                ),
                login,
                clock
        );

        assertNotFound(
                () -> provider.verify(
                        other.issue(identity, NOW.plusSeconds(3600)).value(),
                        1L,
                        ID
                )
        );
    }

    @Test
    @DisplayName("로그인 토큰과 대화 토큰은 서로 바꿔 사용할 수 없다")
    void loginAndChatTokens_cannotBeInterchanged() {
        var config = new JwtConfig();
        var key = config.jwtSigningKey(login);
        var loginEncoder = config.jwtEncoder(key);
        var loginDecoder = config.jwtDecoder(key, login, clock);

        var access = new AccessTokenProvider(
                loginEncoder,
                login,
                clock
        );

        assertNotFound(
                () -> provider.verify(
                        access.issue(1L).value(),
                        1L,
                        ID
                )
        );

        assertThrows(
                JwtException.class,
                () -> loginDecoder.decode(
                        provider.issue(identity, NOW.plusSeconds(3600)).value()
                )
        );

        assertEquals(
                "1",
                loginDecoder.decode(
                        access.issue(1L).value()
                ).getSubject()
        );
    }

    @Test
    @DisplayName("로그인 키·발급자 재사용과 잘못된 키 설정은 시작 단계에서 거절한다")
    void configuration_rejectsUnsafeSettings() {
        for (var setting : List.of(
                new ChatConversationTokenProperties(
                        login.secretBase64(),
                        properties.issuer()
                ),
                new ChatConversationTokenProperties(
                        properties.secretBase64(),
                        login.issuer()
                ),
                new ChatConversationTokenProperties(
                        "!",
                        properties.issuer()
                ),
                new ChatConversationTokenProperties(
                        "AA==",
                        properties.issuer()
                )
        )) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ChatConversationTokenProvider(
                            setting,
                            login,
                            clock
                    )
            );
        }

    }

    @Test
    @DisplayName("사용자 ID 안전 범위와 UUID v4 형식을 검증한다")
    void identity_checksIdBoundariesAndUuidFormat() {
        assertEquals(
                ChatConversationIdentity.MAX_USER_ID,
                ChatConversationIdentity.requireUserId(
                        ChatConversationIdentity.MAX_USER_ID
                )
        );

        for (Long id : Arrays.asList(
                null,
                0L,
                -1L,
                9_007_199_254_740_992L
        )) {
            assertEquals(
                    ErrorCode.INVALID_REQUEST,
                    assertThrows(
                            BusinessException.class,
                            () -> ChatConversationIdentity.requireUserId(id)
                    ).getErrorCode()
            );
        }

        for (String id : Arrays.asList(
                null,
                "1-1-4-8-1",
                " " + ID,
                "df68e1e3-6c35-1cda-8250-1092cf78ea23",
                "df68e1e3-6c35-4cda-7250-1092cf78ea23"
        )) {
            assertThrows(
                    BusinessException.class,
                    () -> ChatConversationIdentity.parseConversationId(id)
            );
        }

        assertEquals(
                ID,
                ChatConversationIdentity.parseConversationId(
                        ID.toString().toUpperCase(Locale.ROOT)
                )
        );
    }

    private String sign(Map<String, Object> claims) {
        var encoder = NimbusJwtEncoder.withSecretKey(
                new SecretKeySpec(
                        Base64.getDecoder()
                                .decode(properties.secretBase64()),
                        "HmacSHA256"
                )
        ).algorithm(MacAlgorithm.HS256).build();

        return encoder.encode(
                JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        JwtClaimsSet.builder()
                                .claims(values -> values.putAll(claims))
                                .build()
                )
        ).getTokenValue();
    }

    private void assertNotFound(
            org.junit.jupiter.api.function.Executable executable
    ) {
        BusinessException exception = assertThrows(
                BusinessException.class,
                executable
        );

        assertEquals(
                ErrorCode.NOT_FOUND,
                exception.getErrorCode()
        );
    }

    private static String base64(int firstByte) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) firstByte;

        return Base64.getEncoder().encodeToString(bytes);
    }
}
