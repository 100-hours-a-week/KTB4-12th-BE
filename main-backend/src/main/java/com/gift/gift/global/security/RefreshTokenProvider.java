package com.gift.gift.global.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class RefreshTokenProvider {

    private static final int TOKEN_BYTES = 32;
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final SecureRandom secureRandom =
            new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    public String hash(String token) {
        Objects.requireNonNull(
                token,
                "토큰은 null일 수 없습니다."
        );

        try {
            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                            token.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 알고리즘을 사용할 수 없습니다.",
                    exception
            );
        }
    }

    public boolean isValidFormat(String token) {
        return token != null
                && TOKEN_PATTERN.matcher(token).matches();
    }
}
