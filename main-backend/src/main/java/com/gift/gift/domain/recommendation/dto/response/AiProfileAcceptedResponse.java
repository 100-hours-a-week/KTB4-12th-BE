package com.gift.gift.domain.recommendation.dto.response;

import com.gift.gift.domain.recommendation.entity.RecipientProfileStatus;

public record AiProfileAcceptedResponse(
        Long recipientUserId,
        long sourceVersion,
        RecipientProfileStatus profileStatus
) {
}
