package com.gift.gift.domain.gift.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.gift.repository.GiftExpirationCandidate;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.service.PaymentCancellationService;

@Slf4j
@Service
@RequiredArgsConstructor
public class GiftExpirationService {

    private static final int BATCH_SIZE = 1_000;

    private static final String IDEMPOTENCY_KEY_PREFIX = "GIFT_EXPIRATION:";

    private final GiftHistoryRepository giftHistoryRepository;
    private final OrderRepository orderRepository;
    private final PaymentCancellationService cancellationService;
    private final Clock clock;

    public void expireAll() {
        LocalDateTime cutoff = LocalDateTime.now(clock);

        Pageable limit = PageRequest.of(0, BATCH_SIZE);

        LocalDateTime cursorExpiresAt = null;
        Long cursorId = null;

        int attemptedCount = 0;
        int completedCount = 0;
        int failedCount = 0;
        int batchCount = 0;

        while (true) {
            List<GiftExpirationCandidate> candidates = findBatch(cutoff, cursorExpiresAt, cursorId, limit);

            if (candidates.isEmpty()) {
                break;
            }

            batchCount++;

            for (GiftExpirationCandidate candidate : candidates) {
                attemptedCount++;

                if (expire(candidate)) {
                    completedCount++;
                } else {
                    failedCount++;
                }
            }

            GiftExpirationCandidate last = candidates.getLast();

            cursorExpiresAt = last.expiresAt();
            cursorId = last.giftId();

            if (candidates.size() < BATCH_SIZE) {
                break;
            }
        }

        log.info(
                "미수락 선물 만료 처리를 완료했습니다. "
                        + "cutoff={}, batchCount={}, "
                        + "attemptedCount={}, completedCount={}, "
                        + "failedCount={}",
                cutoff,
                batchCount,
                attemptedCount,
                completedCount,
                failedCount
        );
    }

    private List<GiftExpirationCandidate> findBatch(
            LocalDateTime cutoff,
            LocalDateTime cursorExpiresAt,
            Long cursorId,
            Pageable limit
    ) {
        if (cursorExpiresAt == null || cursorId == null) {
            return giftHistoryRepository.findExpired(
                    cutoff,
                    limit
            );
        }

        return giftHistoryRepository.findExpiredAfter(
                cutoff,
                cursorExpiresAt,
                cursorId,
                limit
        );
    }

    private boolean expire(
            GiftExpirationCandidate candidate
    ) {
        try {
            Order order = orderRepository
                    .findById(candidate.orderRecordId())
                    .orElseThrow(() ->
                            new IllegalStateException("만료 선물의 주문을 찾을 수 없습니다. " + "giftId=" + candidate.giftId()));

            cancellationService.cancel(
                    candidate.senderId(),
                    order.getOrderId(),
                    idempotencyKey(candidate.giftId()),
                    CancellationSource.GIFT_EXPIRATION
            );

            return true;
        } catch (RuntimeException exception) {
            log.error(
                    "미수락 선물 만료 처리에 실패했습니다. "
                            + "giftId={}, orderRecordId={}",
                    candidate.giftId(),
                    candidate.orderRecordId(),
                    exception
            );

            return false;
        }
    }

    private String idempotencyKey(Long giftId) {
        return IDEMPOTENCY_KEY_PREFIX + giftId;
    }
}
