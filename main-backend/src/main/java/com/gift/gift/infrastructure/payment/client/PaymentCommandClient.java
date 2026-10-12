package com.gift.gift.infrastructure.payment.client;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import com.gift.gift.infrastructure.payment.dto.PaymentConfirmRequest;
import com.gift.gift.infrastructure.payment.dto.PaymentResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerResponse;

@HttpExchange(accept = MediaType.APPLICATION_JSON_VALUE)
public interface PaymentCommandClient {

    String SCENARIO_HEADER = "X-Payment-Scenario";

    @PostExchange(
            url = "/payments/confirm",
            contentType = MediaType.APPLICATION_JSON_VALUE
    )
    PaymentServerResponse<PaymentResponse> confirm(
            @RequestBody PaymentConfirmRequest request,
            @RequestHeader(value = SCENARIO_HEADER, required = false)
            String scenario
    );
}
