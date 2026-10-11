package com.gift.gift.domain.recommendation.dto.request;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiProfileRequest(
        Long recipientUserId,
        long sourceVersion,
        List<DislikedCategoryRequest> dislikedCategories,
        String giftPreference,
        List<ProfileReviewRequest> reviews
) {

    public AiProfileRequest {
        Objects.requireNonNull(
                recipientUserId,
                "수신자 ID는 null일 수 없습니다."
        );
        Objects.requireNonNull(
                dislikedCategories,
                "비선호 카테고리 목록은 null일 수 없습니다."
        );

        if (recipientUserId <= 0) {
            throw new IllegalArgumentException(
                    "수신자 ID는 양수여야 합니다."
            );
        }

        if (sourceVersion < 0) {
            throw new IllegalArgumentException(
                    "요청 버전은 음수일 수 없습니다."
            );
        }

        dislikedCategories = List.copyOf(dislikedCategories);
        reviews = List.copyOf(Objects.requireNonNull(reviews, "리뷰 목록은 null일 수 없습니다."));
        if (reviews.size() > 10) {
            throw new IllegalArgumentException("프로파일 리뷰는 최대 10건입니다.");
        }
    }
}
