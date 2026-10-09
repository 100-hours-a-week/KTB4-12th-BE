package com.gift.gift.domain.user.dto.response;

public record EmailAvailabilityResponse(
        boolean available
) {

    public static EmailAvailabilityResponse from(boolean available) {
        return new EmailAvailabilityResponse(available);
    }
}
