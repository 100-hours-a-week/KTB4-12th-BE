package com.gift.gift.domain.gift.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.friend.service.FriendQueryService;
import com.gift.gift.domain.gift.dto.request.GiftPreflightRequest;
import com.gift.gift.domain.gift.dto.response.GiftPreflightResponse;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.gift.support.GiftPolicy;
import com.gift.gift.domain.preference.dto.response.PreferenceWarningResult;
import com.gift.gift.domain.preference.service.PreferenceQueryService;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.service.ProductQueryService;
import com.gift.gift.domain.user.service.UserQueryService;
import com.gift.gift.domain.user.support.ActiveUserSummary;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class GiftService {

    private final GiftHistoryRepository giftHistoryRepository;
    private final UserQueryService userQueryService;
    private final FriendQueryService friendQueryService;
    private final ProductQueryService productQueryService;
    private final PreferenceQueryService preferenceQueryService;

    @Transactional(readOnly = true)
    public GiftPreflightResponse preflight(Long senderId, GiftPreflightRequest request) {
        if (senderId.equals(request.recipientUserId())) {
            throw new GiftException(ErrorCode.INVALID_REQUEST);
        }

        ActiveUserSummary recipient = userQueryService.findActiveUser(request.recipientUserId())
                .orElseThrow(() -> new GiftException(ErrorCode.RECIPIENT_NOT_FOUND));

        if (!friendQueryService.areFriends(senderId, recipient.userId())) {
            throw new GiftException(ErrorCode.RECIPIENT_NOT_FRIEND);
        }

        Product product = productQueryService.findAvailableProduct(request.productId())
                .orElseThrow(() -> new GiftException(ErrorCode.PRODUCT_NOT_FOUND));

        if (product.getQuantity() < request.quantity()) {
            throw new GiftException(ErrorCode.INSUFFICIENT_STOCK);
        }

        PreferenceWarningResult preferenceWarningResult = preferenceQueryService.findMatchingWarning(recipient.userId(),
                product.getCategory().getId()).orElse(null);

        BigDecimal totalPrice = product.getPrice().multiply(BigDecimal.valueOf(request.quantity()));
        int maxOrderQuantity = Math.min(product.getQuantity(), GiftPolicy.MAX_QUANTITY);

        return GiftPreflightResponse.from(
                new GiftPreflightResponse.Recipient(recipient.userId(), recipient.name()),
                new GiftPreflightResponse.Product(
                        product.getId(),
                        product.getPrice(),
                        request.quantity(),
                        totalPrice,
                        maxOrderQuantity
                ),
                preferenceWarningResult == null ? null : new GiftPreflightResponse.PreferenceWarning(
                        preferenceWarningResult.categoryId(),
                        preferenceWarningResult.categoryName()
                )
        );
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
