package com.gift.payment.payment.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

@Component
public class PaymentCancelRequestHasher {

    private static final String ALGORITHM = "SHA-256";

    public String hash(String orderId) {
        String source = "v1|orderId=" + orderId;

        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);

            return HexFormat.of().formatHex(
                    digest.digest(source.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("결제 취소 요청 해시를 생성할 수 없습니다.", exception);
        }
    }
}
