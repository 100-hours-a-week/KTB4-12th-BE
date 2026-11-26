package com.gift.gift.domain.product.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.product.cache.ProductViewRedisRepository;
import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.product.repository.ProductViewStatsRepository;
import com.gift.gift.domain.product.search.ProductSearchOutboxWriter;

@Slf4j
@Service
public class ProductViewSyncService {

    private final ProductViewRedisRepository productViewRedisRepository;
    private final ProductRepository productRepository;
    private final ProductViewStatsRepository productViewStatsRepository;
    private final ProductSearchOutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;
    private final Timer syncTimer;
    private final Counter syncedCounter;
    private final AtomicLong lastSuccessSeconds = new AtomicLong();
    private final int chunkSize;

    public ProductViewSyncService(
            ProductViewRedisRepository productViewRedisRepository,
            ProductRepository productRepository,
            ProductViewStatsRepository productViewStatsRepository,
            ProductSearchOutboxWriter outboxWriter,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry,
            @Value("${app.product-view.sync-chunk-size:100}") int chunkSize
    ) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("조회수 반영 묶음 크기는 양수여야 합니다.");
        }

        this.productViewRedisRepository = productViewRedisRepository;
        this.productRepository = productRepository;
        this.productViewStatsRepository = productViewStatsRepository;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.chunkSize = chunkSize;

        this.syncTimer = Timer.builder("gift.product.view.sync")
                .description("Redis 조회수를 DB 통계에 반영하는 시간")
                .register(meterRegistry);
        this.syncedCounter = Counter.builder("gift.product.view.synced")
                .description("DB 통계에 반영한 상품 수")
                .register(meterRegistry);
        Gauge.builder("gift.product.view.sync.last.success", lastSuccessSeconds, AtomicLong::doubleValue)
                .baseUnit("seconds")
                .description("마지막으로 반영에 성공한 시각(epoch 초)")
                .register(meterRegistry);
        Gauge.builder("gift.product.view.pending", productViewRedisRepository,
                        ProductViewRedisRepository::countPending)
                .description("DB에 아직 반영하지 않은 조회수가 있는 상품 수")
                .register(meterRegistry);
    }

    public int sync() {
        Integer result = syncTimer.record(this::syncAll);
        int syncedCount = result == null ? 0 : result;

        syncedCounter.increment(syncedCount);
        lastSuccessSeconds.set(Instant.now().getEpochSecond());

        return syncedCount;
    }

    private int syncAll() {
        if (!productViewRedisRepository.moveToSyncing()) {
            return 0;
        }

        Map<Long, Long> viewCounts = new TreeMap<>();
        List<String> invalidIds = new ArrayList<>();

        productViewRedisRepository.findSyncing().forEach((idText, countText) -> {
            try {
                long productId = Long.parseLong(idText);
                long count = Long.parseLong(countText);
                if (productId <= 0 || count <= 0) {
                    throw new NumberFormatException("양수가 아닙니다.");
                }
                viewCounts.put(productId, count);
            } catch (NumberFormatException exception) {
                invalidIds.add(idText);
            }
        });

        if (!invalidIds.isEmpty()) {
            log.warn("올바르지 않은 조회수 항목을 버립니다. count={}", invalidIds.size());
            productViewRedisRepository.deleteSyncing(invalidIds);
        }

        List<Long> productIds = new ArrayList<>(viewCounts.keySet());
        int syncedCount = 0;

        for (int from = 0; from < productIds.size(); from += chunkSize) {
            List<Long> chunk = productIds.subList(from, Math.min(from + chunkSize, productIds.size()));

            Integer chunkSynced = transactionTemplate.execute(status -> applyChunk(chunk, viewCounts));

            productViewRedisRepository.deleteSyncing(chunk.stream().map(String::valueOf).toList());
            syncedCount += chunkSynced == null ? 0 : chunkSynced;
        }

        return syncedCount;
    }

    private int applyChunk(List<Long> productIds, Map<Long, Long> viewCounts) {
        int syncedCount = 0;

        for (Long productId : productIds) {
            if (productRepository.incrementSearchVersionIfActive(productId) == 0) {
                log.debug("비활성 상품의 조회수 반영을 건너뜁니다. productId={}", productId);
                continue;
            }

            int count = (int) Math.min(viewCounts.get(productId), Integer.MAX_VALUE);
            if (productViewStatsRepository.incrementViewsIfActive(productId, count) != 1) {
                log.warn("조회수 통계 행이 없어 반영을 건너뜁니다. productId={}", productId);
                continue;
            }

            outboxWriter.enqueue(productId);
            syncedCount++;
        }

        return syncedCount;
    }
}
