package com.gift.gift.domain.review.dto.response;

public record DeleteReviewResponse(
        Long giftId,
        String reviewStatus
) {

    private static final String NOT_WRITTEN = "NOT_WRITTEN";

    public static DeleteReviewResponse from(Long giftId) {
        return new DeleteReviewResponse(
                giftId,
                NOT_WRITTEN
        );
    }
}
