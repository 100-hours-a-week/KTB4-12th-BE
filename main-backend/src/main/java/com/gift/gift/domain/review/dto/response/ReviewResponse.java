package com.gift.gift.domain.review.dto.response;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import com.gift.gift.domain.review.entity.Review;

public record ReviewResponse(ReviewDetail review) {

    public static ReviewResponse from(Review review) {
        return new ReviewResponse(
                new ReviewDetail(
                        review.getId(),
                        review.getGiftHistory().getId(),
                        review.getRating(),
                        review.getReviewText(),
                        review.getCreatedAt(),
                        review.getUpdatedAt()
                )
        );
    }

    public record ReviewDetail(
            Long reviewId,
            Long giftId,
            Integer rating,
            String content,

            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
            LocalDateTime createdAt,

            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
            LocalDateTime updatedAt
    ) {
    }
}
