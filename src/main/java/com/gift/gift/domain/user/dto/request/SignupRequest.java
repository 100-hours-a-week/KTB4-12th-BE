package com.gift.gift.domain.user.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.gift.gift.domain.user.support.EmailNormalizer;
import com.gift.gift.domain.user.validation.UniqueTermConsentIds;
import com.gift.gift.domain.user.validation.ValidSignupBirth;
import com.gift.gift.global.exception.ValidationErrorReason;

public record SignupRequest(
        @NotBlank(message = ValidationErrorReason.Message.REQUIRED)
        @Size(
                max = 30,
                message = ValidationErrorReason.Message.MAX_LENGTH_EXCEEDED
        )
        @Pattern(
                regexp = "^[가-힣A-Za-z](?:[가-힣A-Za-z ]*[가-힣A-Za-z])?$",
                message = ValidationErrorReason.Message.INVALID_FORMAT
        )
        String name,

        @NotBlank(message = ValidationErrorReason.Message.REQUIRED)
        @ValidSignupBirth
        String birth,

        @NotBlank(message = ValidationErrorReason.Message.REQUIRED)
        @Email(message = ValidationErrorReason.Message.INVALID_FORMAT)
        @Size(
                max = 254,
                message = ValidationErrorReason.Message.MAX_LENGTH_EXCEEDED
        )
        String email,

        @NotBlank(message = ValidationErrorReason.Message.REQUIRED)
        @Size(min = 8, message = ValidationErrorReason.Message.TOO_SHORT)
        @Size(max = 64, message = ValidationErrorReason.Message.TOO_LONG)
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*[0-9])"
                        + "(?=.*[!@#$%^&*()_+\\-=])"
                        + "[A-Za-z0-9!@#$%^&*()_+\\-=]+$",
                message = ValidationErrorReason.Message.INVALID_FORMAT
        )
        String password,

        @NotNull(message = ValidationErrorReason.Message.REQUIRED)
        @UniqueTermConsentIds
        @Valid
        List<
                @NotNull(message = ValidationErrorReason.Message.REQUIRED)
                        SignupTermConsentRequest
                > termConsents
) {
    public SignupRequest {
        if (email != null) {
            email = EmailNormalizer.normalize(email);
        }
    }

    @Override
    public String toString() {
        return "SignupRequest[REDACTED]";
    }
}
