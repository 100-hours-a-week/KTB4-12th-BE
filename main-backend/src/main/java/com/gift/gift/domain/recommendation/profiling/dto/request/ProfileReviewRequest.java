package com.gift.gift.domain.recommendation.profiling.dto.request;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProfileReviewRequest(Long productId, Integer rating, String reviewText) {

    public ProfileReviewRequest {
        Objects.requireNonNull(productId, "상품 ID는 null일 수 없습니다.");
        Objects.requireNonNull(rating, "별점은 null일 수 없습니다.");
        if (productId <= 0 || rating < 1 || rating > 5) {
            throw new IllegalArgumentException("상품 ID는 양수, 별점은 1~5여야 합니다.");
        }
        if (reviewText != null && reviewText.isBlank()) {
            reviewText = null;
        }
    }
}
