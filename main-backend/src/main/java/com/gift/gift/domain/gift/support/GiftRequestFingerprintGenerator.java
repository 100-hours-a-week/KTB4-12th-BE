package com.gift.gift.domain.gift.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.gift.gift.domain.gift.dto.request.GiftCreateRequest;

@Component
public class GiftRequestFingerprintGenerator {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String FINGERPRINT_VERSION = "v1";

    public String generate(GiftCreateRequest request) {
        Objects.requireNonNull(request, "선물 생성 요청은 필수입니다.");

        String canonicalRequest = String.join("\n",
                "version=" + FINGERPRINT_VERSION,
                "productId=" + request.productId(),
                "recipientUserId=" + request.recipientUserId(),
                "quantity=" + request.quantity(),
                "expectedUnitPrice=" + request.expectedUnitPrice().stripTrailingZeros().toPlainString()
        );

        try {
            MessageDigest messageDigest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = messageDigest.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("요청 fingerprint를 생성할 수 없습니다.", exception);
        }
    }
}
