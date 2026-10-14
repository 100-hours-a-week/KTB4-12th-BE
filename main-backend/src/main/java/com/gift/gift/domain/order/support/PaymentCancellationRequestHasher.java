package com.gift.gift.domain.order.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public class PaymentCancellationRequestHasher {

    private static final String ALGORITHM = "SHA-256";

    public String hash(String orderId) {

        Objects.requireNonNull(orderId, "주문번호는 null일 수 없습니다.");

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
