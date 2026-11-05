package com.gift.gift.domain.recommendation.profiling.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.gift.gift.domain.preference.entity.UserDislikeCategory;
import com.gift.gift.domain.preference.entity.UserGiftPreference;
import com.gift.gift.domain.preference.repository.UserDislikeCategoryRepository;
import com.gift.gift.domain.preference.repository.UserGiftPreferenceRepository;
import com.gift.gift.domain.recommendation.profiling.dto.request.AiProfileRequest;
import com.gift.gift.domain.recommendation.profiling.dto.request.DislikedCategoryRequest;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfileReviewRequest;
import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfile;
import com.gift.gift.domain.review.repository.ProfileReviewRow;
import com.gift.gift.domain.review.repository.ReviewRepository;

@Component
@RequiredArgsConstructor
public class ProfileRequestFactory {

    private final UserDislikeCategoryRepository dislikeRepository;
    private final UserGiftPreferenceRepository giftPreferenceRepository;
    private final ReviewRepository reviewRepository;

    public AiProfileRequest create(
            RecipientProfile profile,
            long sourceVersion
    ) {
        Long recipientUserId = profile.getRecipient().getId();

        List<DislikedCategoryRequest> dislikedCategories =
                dislikeRepository
                        .findAllActiveByUserIdWithCategory(recipientUserId)
                        .stream()
                        .map(UserDislikeCategory::getCategory)
                        .map(DislikedCategoryRequest::from)
                        .toList();

        String giftPreference = giftPreferenceRepository
                .findByUser_IdAndDeletedAtIsNull(recipientUserId)
                .map(UserGiftPreference::getPreference)
                .orElse(null);

        Map<Long, ProfileReviewRequest> reviewsByProduct =
                new LinkedHashMap<>();

        for (ProfileReviewRow review
                : reviewRepository.findLatestProfileReviews(
                recipientUserId,
                PageRequest.of(0, 10)
        )) {
            reviewsByProduct.putIfAbsent(
                    review.productId(),
                    new ProfileReviewRequest(
                            review.productId(),
                            review.rating(),
                            review.reviewText()
                    )
            );
        }

        return new AiProfileRequest(
                recipientUserId,
                sourceVersion,
                dislikedCategories,
                giftPreference,
                List.copyOf(reviewsByProduct.values())
        );
    }
}
