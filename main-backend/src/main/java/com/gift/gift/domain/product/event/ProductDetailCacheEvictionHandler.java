package com.gift.gift.domain.product.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.gift.gift.domain.product.cache.ProductDetailCacheRepository;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductDetailCacheEvictionHandler {

    private static final int MAX_ATTEMPTS = 3;

    private final ProductDetailCacheRepository cacheRepository;

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = false
    )
    public void handle(ProductMetadataChangedEvent event) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                cacheRepository.deleteById(event.productId());
                return;
            } catch (DataAccessException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    // shortcut: 재시도는 메모리 내에서만 수행한다.
                    // 장애·재시작 후 복구 보장이 필요하면 영속적인 재시도 기록이 필요하다.
                    log.error(
                            "상품 캐시 무효화 최종 실패. productId={}, attempts={}",
                            event.productId(),
                            MAX_ATTEMPTS,
                            exception
                    );
                    return;
                }

                log.warn(
                        "상품 캐시 무효화 실패. 재시도합니다. productId={}, attempt={}",
                        event.productId(),
                        attempt,
                        exception
                );
            }
        }
    }
}
