package com.gift.gift.domain.product.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.product.cache.ProductViewRedisRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductViewService {

    private final ProductViewRedisRepository productViewRedisRepository;
    private final MeterRegistry meterRegistry;

    public void recordProductView(Long userId, Long productId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("사용자 ID는 양수여야 합니다.");
        }
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException("상품 ID는 양수여야 합니다.");
        }

        try {
            boolean incremented = productViewRedisRepository.incrementView(userId, productId);
            count(incremented ? "incremented" : "duplicate");
        } catch (DataAccessException | IllegalStateException exception) {
            count("error");
            log.warn("상품 조회수 증가 실패. 상세 응답은 유지합니다. productId={}", productId, exception);
        }
    }

    private void count(String result) {
        meterRegistry.counter("gift.product.view.increment", "result", result).increment();
    }
}
