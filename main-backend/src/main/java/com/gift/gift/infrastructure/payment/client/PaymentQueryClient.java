package com.gift.gift.infrastructure.payment.client;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import com.gift.gift.infrastructure.payment.dto.PaymentResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerResponse;

@HttpExchange(accept = MediaType.APPLICATION_JSON_VALUE)
public interface PaymentQueryClient {

    @GetExchange("/payments/orders/{orderId}")
    PaymentServerResponse<PaymentResponse> getByOrderId(
            @PathVariable String orderId
    );
}
