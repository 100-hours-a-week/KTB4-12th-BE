package com.gift.gift.domain.recommendation.profiling.service;

import java.util.List;
import java.util.Objects;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.profiling.exception.RecommendationErrorCode;
import com.gift.gift.domain.recommendation.profiling.exception.RecommendationException;
import com.gift.gift.domain.recommendation.profiling.repository.RecipientProfileRepository;

@Service
@RequiredArgsConstructor
public class ProfileCallbackService {

    private final RecipientProfileRepository profileRepository;
    private final ProfileRecommendationWriter recommendationWriter;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void saveCallback(
            Long pathRecipientId,
            Long bodyRecipientId,
            long sourceVersion,
            List<Long> recommendedProductIds
    ) {
        if (!Objects.equals(pathRecipientId, bodyRecipientId)) {
            throw new RecommendationException(
                    RecommendationErrorCode.RECIPIENT_ID_MISMATCH
            );
        }

        RecipientProfile profile = profileRepository
                .findByRecipientIdForUpdate(pathRecipientId)
                .orElseThrow(() -> new RecommendationException(
                        RecommendationErrorCode.INVALID_CALLBACK_RECIPIENT
                ));

        recommendationWriter.replace(
                profile,
                sourceVersion,
                recommendedProductIds
        );
    }
}
