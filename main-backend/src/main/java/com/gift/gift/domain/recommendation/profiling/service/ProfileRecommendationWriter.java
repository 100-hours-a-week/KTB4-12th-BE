package com.gift.gift.domain.recommendation.profiling.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.profiling.entity.RecipientRecommendedProduct;
import com.gift.gift.domain.recommendation.profiling.event.RecommendationAnalysisCompletedEvent;
import com.gift.gift.domain.recommendation.profiling.repository.RecipientRecommendedProductRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileRecommendationWriter {

    private final RecipientRecommendedProductRepository recommendationRepository;
    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.MANDATORY)
    public void replace(
            RecipientProfile profile,
            long sourceVersion,
            List<Long> recommendedProductIds
    ) {
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

        List<Long> droppedProductIds = new ArrayList<>();
        List<RecipientRecommendedProduct> recommendations =
                new ArrayList<>(recommendedProductIds.size());

        for (Long productId : recommendedProductIds) {
            Product product = productsById.get(productId);

            if (product == null) {
                droppedProductIds.add(productId);
                continue;
            }

            recommendations.add(new RecipientRecommendedProduct(
                    profile,
                    product,
                    recommendations.size() + 1,
                    sourceVersion
            ));
        }

        Long recipientId = profile.getRecipient().getId();

        recommendationRepository.deleteAllByRecipientId(recipientId);
        recommendationRepository.saveAllAndFlush(recommendations);

        profile.markCompleted(sourceVersion);

        eventPublisher.publishEvent(
                new RecommendationAnalysisCompletedEvent(
                        recipientId,
                        profile.getAnalyzedSourceVersion()
                )
        );

        if (!droppedProductIds.isEmpty()) {
            log.warn(
                    "AI 추천 결과에서 없는·삭제된 상품을 제외했습니다. "
                            + "recipientUserId={}, sourceVersion={}, "
                            + "droppedProductIds={}",
                    recipientId,
                    sourceVersion,
                    droppedProductIds
            );
        }
    }
}
