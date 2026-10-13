package com.gift.gift.domain.order.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.gift.gift.domain.order.dto.request.PaymentCreateRequest;

@Component
public class PaymentRequestHasher {

    public String hash(PaymentCreateRequest request) {

        Objects.requireNonNull(request, "결제 요청은 null일 수 없습니다.");

        String source = "v1"
                + "|productId=" + request.productId()
                + "|recipientUserId=" + request.recipientUserId()
                + "|quantity=" + request.quantity()
                + "|expectedUnitPrice=" + request.expectedUnitPrice();

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(source.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 해시 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
