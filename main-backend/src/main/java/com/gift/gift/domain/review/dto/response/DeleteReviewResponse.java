package com.gift.gift.domain.review.dto.response;

public record DeleteReviewResponse(
        Long giftId
) {

    public static DeleteReviewResponse from(Long giftId) {
        return new DeleteReviewResponse(giftId);
    }
}
