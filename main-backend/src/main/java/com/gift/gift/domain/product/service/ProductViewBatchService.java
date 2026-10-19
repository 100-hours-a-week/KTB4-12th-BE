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
import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.product.search.ProductSearchOutboxWriter;
import com.gift.gift.domain.product.repository.ProductViewEventRepository;
import com.gift.gift.domain.product.repository.ProductViewStatsRepository;

@Service
public class ProductViewBatchService {

    private final ProductViewStatsRepository productViewStatsRepository;
    private final ProductViewEventRepository productViewEventRepository;
    private final ProductRepository productRepository;
    private final ProductSearchOutboxWriter outboxWriter;
    private final int batchSize;

    public ProductViewBatchService(
            ProductViewStatsRepository productViewStatsRepository,
            ProductViewEventRepository productViewEventRepository,
            ProductRepository productRepository,
            ProductSearchOutboxWriter outboxWriter,
            @Value("${app.product-view.batch-size:500}") int batchSize
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("상품 조회 Batch 크기는 양수여야 합니다.");
        }

        this.productViewStatsRepository = productViewStatsRepository;
        this.productViewEventRepository = productViewEventRepository;
        this.productRepository = productRepository;
        this.outboxWriter = outboxWriter;
        this.batchSize = batchSize;
    }

    private void incrementViews(Long productId, int viewCount) {
        // 상품 → 통계 순서로 잠가 판매·삭제 경로와 잠금 순서를 맞춘다.
        int versionUpdated = productRepository.incrementSearchVersionIfActive(productId);
        int updated = productViewStatsRepository.incrementViewsIfActive(productId, viewCount);
        if (updated == 0 && !productViewStatsRepository.existsById(productId)) {
            throw new IllegalStateException("상품 조회수 통계 행이 없습니다.");
        }
        if (updated != versionUpdated) {
            throw new IllegalStateException("상품과 조회수 통계의 활성 상태가 일치하지 않습니다.");
        }
        if (updated == 1) {
            outboxWriter.enqueue(productId);
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
