package com.gift.gift.domain.recommendation.profiling.dto.response;

import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfileStatus;

public record AiProfileAcceptedResponse(
        Long recipientUserId,
        long sourceVersion,
        RecipientProfileStatus profileStatus
) {
}
