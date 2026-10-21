package com.gift.payment.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record PaymentConfirmRequest(

        @NotBlank
        @Pattern(
                regexp = "^[0-9a-fA-F]{8}-"
                        + "[0-9a-fA-F]{4}-"
                        + "[1-5][0-9a-fA-F]{3}-"
                        + "[89abAB][0-9a-fA-F]{3}-"
                        + "[0-9a-fA-F]{12}$"
        )
        String orderId,

        @NotNull
        @Positive
        Long amount
) {
}
