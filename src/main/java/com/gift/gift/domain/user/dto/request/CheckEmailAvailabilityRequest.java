package com.gift.gift.domain.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.gift.gift.domain.user.support.EmailNormalizer;
import com.gift.gift.global.exception.ValidationErrorReason;

public record CheckEmailAvailabilityRequest(

        @NotBlank(message = ValidationErrorReason.Message.REQUIRED)
        @Email(message = ValidationErrorReason.Message.INVALID_FORMAT)
        @Size(
                max = 254,
                message = ValidationErrorReason.Message.MAX_LENGTH_EXCEEDED
        )
        String email
) {

    public CheckEmailAvailabilityRequest {
        if (email != null) {
            email = EmailNormalizer.normalize(email);
        }
    }
}
