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
                "클라이언트 IP는 null일 수 없습니다."
        );

        String normalizedIp = clientIp.strip();

        if (normalizedIp.isBlank()) {
            throw new IllegalArgumentException(
                    "클라이언트 IP는 비어 있을 수 없습니다."
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
                    "요청 제한 식별자를 생성하지 못했습니다.",
                    exception
            );
        }
    }
}
