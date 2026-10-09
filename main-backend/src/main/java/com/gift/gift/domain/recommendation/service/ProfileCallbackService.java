package com.gift.gift.domain.recommendation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.recommendation.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.entity.RecipientRecommendedProduct;
import com.gift.gift.domain.recommendation.exception.RecommendationErrorCode;
import com.gift.gift.domain.recommendation.exception.RecommendationException;
import com.gift.gift.domain.recommendation.repository.RecipientProfileRepository;
import com.gift.gift.domain.recommendation.repository.RecipientRecommendedProductRepository;

@Service
@RequiredArgsConstructor
public class ProfileCallbackService {

    private final RecipientProfileRepository profileRepository;
    private final RecipientRecommendedProductRepository recommendationRepository;
    private final ProductRepository productRepository;

    @Transactional
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

        if (!profile.shouldApplyCallback(sourceVersion)) {
            return;
        }

        List<Product> products = recommendedProductIds.isEmpty()
                ? List.of()
                : productRepository.findAllByIdInAndDeletedAtIsNull(
                recommendedProductIds
        );

        Map<Long, Product> productsById = products.stream()
                .collect(Collectors.toMap(
                        Product::getId,
                        Function.identity()
                ));

        if (productsById.size() != recommendedProductIds.size()) {
            throw new RecommendationException(
                    RecommendationErrorCode.INVALID_RECOMMENDED_PRODUCT
            );
        }

        recommendationRepository.deleteAllByRecipientId(pathRecipientId);

        List<RecipientRecommendedProduct> recommendations =
                new ArrayList<>(recommendedProductIds.size());

        for (int index = 0; index < recommendedProductIds.size(); index++) {
            Long productId = recommendedProductIds.get(index);

            recommendations.add(new RecipientRecommendedProduct(
                    profile,
                    productsById.get(productId),
                    index + 1,
                    sourceVersion
            ));
        }

        recommendationRepository.saveAllAndFlush(recommendations);

        profile.markCompleted(sourceVersion);
    }
}
