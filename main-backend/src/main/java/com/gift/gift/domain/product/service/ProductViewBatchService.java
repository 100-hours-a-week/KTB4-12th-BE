package com.gift.gift.domain.product.service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.product.repository.PendingProductViewEvent;
import com.gift.gift.domain.product.repository.ProductViewEventRepository;
import com.gift.gift.domain.product.repository.ProductViewStatsRepository;

@Service
public class ProductViewBatchService {

    private final ProductViewStatsRepository productViewStatsRepository;
    private final ProductViewEventRepository productViewEventRepository;
    private final int batchSize;

    public ProductViewBatchService(
            ProductViewStatsRepository productViewStatsRepository,
            ProductViewEventRepository productViewEventRepository,
            @Value("${app.product-view.batch-size:500}") int batchSize
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("상품 조회 Batch 크기는 양수여야 합니다.");
        }

        this.productViewStatsRepository = productViewStatsRepository;
        this.productViewEventRepository = productViewEventRepository;
        this.batchSize = batchSize;
    }

    private void incrementViews(Long productId, int viewCount) {
        int updated = productViewStatsRepository.incrementViewsIfActive(productId, viewCount);
        if (updated == 0 && !productViewStatsRepository.existsById(productId)) {
            throw new IllegalStateException("상품 조회수 통계 행이 없습니다.");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int processPendingEvents() {
        List<PendingProductViewEvent> events =
                productViewEventRepository.findPendingForUpdate(batchSize);

        if (events.isEmpty()) {
            return 0;
        }

        Map<Long, Long> eventCountByProduct = events.stream()
                .collect(Collectors.groupingBy(
                        PendingProductViewEvent::getProductId,
                        TreeMap::new,
                        Collectors.counting()
                ));

        eventCountByProduct.forEach((productId, eventCount) ->
                incrementViews(productId, Math.toIntExact(eventCount))
        );

        int processedCount = productViewEventRepository.markProcessed(
                events.stream()
                        .map(PendingProductViewEvent::getId)
                        .toList()
        );

        if (processedCount != events.size()) {
            throw new IllegalStateException(
                    "상품 조회 이벤트 처리 결과가 올바르지 않습니다."
            );
        }

        return processedCount;
    }
}
