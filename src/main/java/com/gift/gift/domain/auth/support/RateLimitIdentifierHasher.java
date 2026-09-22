package com.gift.gift.domain.auth.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.gift.gift.domain.user.support.EmailNormalizer;

@Component
public class RateLimitIdentifierHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKey secretKey;

    public RateLimitIdentifierHasher(
            @Qualifier("loginRateLimitHmacKey")
            SecretKey secretKey
    ) {
        this.secretKey = secretKey;
    }

    public String hashIp(String clientIp) {
        Objects.requireNonNull(
                clientIp,
                "clientIp must not be null"
        );

        String normalizedIp = clientIp.strip();

        if (normalizedIp.isBlank()) {
            throw new IllegalArgumentException(
                    "clientIp must not be blank"
            );
        }

        return hash("ip:" + normalizedIp);
    }

    public String hashEmail(String email) {
        return hash(
                "email:" + EmailNormalizer.normalize(email)
        );
    }

    private String hash(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secretKey);

            byte[] result = mac.doFinal(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(result);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Failed to create rate limit identifier",
                    exception
            );
        }
    }
}
