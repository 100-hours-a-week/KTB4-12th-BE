package com.gift.gift.domain.friend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.gift.gift.global.exception.ValidationErrorReason;

public record FriendCreateRequest(

        @NotNull(message = ValidationErrorReason.Message.REQUIRED)
        @Positive(message = ValidationErrorReason.Message.INVALID_FORMAT)
        Long friendUserId

) {
}
