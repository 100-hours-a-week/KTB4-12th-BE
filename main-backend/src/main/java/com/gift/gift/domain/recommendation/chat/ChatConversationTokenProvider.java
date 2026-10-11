package com.gift.gift.domain.recommendation.chat;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.security.JwtProperties;

@Component
public class ChatConversationTokenProvider {

    public static final String AUDIENCE = "gift-ai-chat-conversation";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final ChatConversationTokenProperties properties;
    private final Clock clock;

    public ChatConversationTokenProvider(
            ChatConversationTokenProperties properties,
            JwtProperties loginProperties,
            Clock clock
    ) {
        byte[] keyBytes;
        byte[] loginKeyBytes;

        try {
            keyBytes = Base64.getDecoder()
                    .decode(properties.secretBase64());

            loginKeyBytes = Base64.getDecoder()
                    .decode(loginProperties.secretBase64());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "JWT 서명 키 설정을 확인해 주세요."
            );
        }

        if (keyBytes.length < 32
                || MessageDigest.isEqual(keyBytes, loginKeyBytes)
                || properties.issuer().equals(loginProperties.issuer())) {
            throw new IllegalArgumentException(
                    "대화 토큰은 별도 서명 키와 발급자를 사용해야 합니다."
            );
        }

        this.properties = properties;
        this.clock = clock;

        var key = new SecretKeySpec(keyBytes, "HmacSHA256");

        // 로그인용 JWT Bean을 추가하거나 교체하지 않는다.
        this.encoder = NimbusJwtEncoder.withSecretKey(key)
                .algorithm(MacAlgorithm.HS256)
                .build();

        var chatDecoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        chatDecoder.setJwtValidator(this::validateClaims);
        this.decoder = chatDecoder;
    }

    public IssuedChatConversationToken issue(
            ChatConversationIdentity identity,
            Instant loginAccessTokenExpiresAt
    ) {
        Instant issuedAt = clock.instant()
                .truncatedTo(ChronoUnit.SECONDS);

        if (loginAccessTokenExpiresAt == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // PR 2는 인증된 로그인 JWT의 exp만 전달하며 요청 Body의 시각을 사용하지 않는다.
        Instant expiresAt = loginAccessTokenExpiresAt.truncatedTo(ChronoUnit.SECONDS);
        if (!expiresAt.isAfter(clock.instant())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(AUDIENCE))
                .subject(identity.conversationId().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(
                        "conversationId",
                        identity.conversationId().toString()
                )
                .claim("senderUserId", identity.senderUserId())
                .claim("recipientUserId", identity.recipientUserId())
                .build();

        String value = encoder.encode(
                JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256)
                                .type("JWT")
                                .build(),
                        claims
                )
        ).getTokenValue();

        return new IssuedChatConversationToken(
                value,
                issuedAt,
                expiresAt
        );
    }

    public ChatConversationIdentity verify(
            String token,
            long currentUserId,
            UUID conversationId
    ) {
        if (token == null || token.isBlank()) {
            throw notFound();
        }

        try {
            Jwt jwt = decoder.decode(token);
            ChatConversationIdentity identity = identityFrom(jwt);

            if (identity.senderUserId() != currentUserId
                    || !identity.conversationId().equals(conversationId)) {
                throw notFound();
            }

            return identity;
        } catch (JwtException | IllegalArgumentException exception) {
            throw notFound();
        }
    }

    private OAuth2TokenValidatorResult validateClaims(Jwt jwt) {
        try {
            Instant now = clock.instant();
            Instant issuedAt = jwt.getIssuedAt();
            Instant expiresAt = jwt.getExpiresAt();

            if (!properties.issuer().equals(jwt.getClaimAsString("iss"))
                    || !List.of(AUDIENCE).equals(jwt.getAudience())
                    || issuedAt == null
                    || expiresAt == null
                    || issuedAt.isAfter(now)
                    || !expiresAt.isAfter(now)
                    || !expiresAt.isAfter(issuedAt)) {
                return invalidToken();
            }

            ChatConversationIdentity identity = identityFrom(jwt);

            if (!identity.conversationId()
                    .toString()
                    .equals(jwt.getSubject())) {
                return invalidToken();
            }

            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException exception) {
            // 토큰 내부 검증 실패 원인을 외부에 구분하여 공개하지 않는다.
            return invalidToken();
        }
    }

    private ChatConversationIdentity identityFrom(Jwt jwt) {
        Object rawConversationId = jwt.getClaim("conversationId");

        if (!(rawConversationId instanceof String value)) {
            throw new IllegalArgumentException(
                    "Invalid conversation claim"
            );
        }

        return new ChatConversationIdentity(
                ChatConversationIdentity.parseConversationId(value),
                userIdClaim(jwt, "senderUserId"),
                userIdClaim(jwt, "recipientUserId")
        );
    }

    private long userIdClaim(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);

        if (!(value instanceof Long)
                && !(value instanceof Integer)) {
            throw new IllegalArgumentException("Invalid user claim");
        }

        return ChatConversationIdentity.requireUserId(
                ((Number) value).longValue()
        );
    }

    private OAuth2TokenValidatorResult invalidToken() {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(
                        "invalid_token",
                        "Invalid conversation token",
                        null
                )
        );
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND);
    }
}
