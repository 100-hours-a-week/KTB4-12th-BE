package com.gift.gift.domain.product.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gift.gift.domain.product.service.ProductViewBatchService;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.scheduling.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ProductViewBatchScheduler {

    private final ProductViewBatchService productViewBatchService;

    @Scheduled(
            fixedDelayString = "${app.product-view.batch-interval:PT1S}"
    )
    public void processProductViewEvents() {
        try {
            int processedCount = productViewBatchService.processPendingEvents();

            if (processedCount > 0) {
                log.info(
                        "상품 조회 이벤트 Batch 처리를 완료했습니다. processedCount={}",
                        processedCount
                );
            }
        } catch (RuntimeException exception) {
            log.error("상품 조회 이벤트 Batch 처리에 실패했습니다.", exception);
        }
    }
}
