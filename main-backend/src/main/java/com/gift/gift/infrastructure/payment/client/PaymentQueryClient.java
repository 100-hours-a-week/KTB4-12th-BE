package com.gift.gift.infrastructure.payment.client;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import com.gift.gift.infrastructure.payment.dto.PaymentCancelResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerResponse;

@HttpExchange(accept = MediaType.APPLICATION_JSON_VALUE)
public interface PaymentQueryClient {

    String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    @GetExchange("/payments/orders/{orderId}")
    PaymentServerResponse<PaymentResponse> getByOrderId(
            @PathVariable String orderId
    );

    @GetExchange("/payments/{paymentKey}/cancellations")
    PaymentServerResponse<PaymentCancelResponse> getCancellation(
            @PathVariable String paymentKey,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey
    );
}
