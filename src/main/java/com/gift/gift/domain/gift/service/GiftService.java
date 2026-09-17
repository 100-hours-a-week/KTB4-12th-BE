package com.gift.gift.domain.gift.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.global.exception.ErrorCode;

@Service
public class GiftService {

    private final GiftHistoryRepository giftHistoryRepository;

    public GiftService(GiftHistoryRepository giftHistoryRepository) {
        this.giftHistoryRepository = giftHistoryRepository;
    }

    public Optional<GiftHistory> findExistingGift(
            Long senderId,
            UUID idempotencyKey,
            String requestFingerprint
    ) {
        Optional<GiftHistory> existingGift = giftHistoryRepository.findBySender_IdAndIdempotencyKey(
                senderId,
                idempotencyKey
        );

        existingGift.ifPresent(giftHistory -> validateFingerprint(giftHistory, requestFingerprint));

        return existingGift;
    }

    private void validateFingerprint(GiftHistory giftHistory, String requestFingerprint) {
        if (!giftHistory.getRequestFingerprint().equals(requestFingerprint)) {
            throw new GiftException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
    }
}
