package com.gift.gift.domain.gift.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.friend.service.FriendQueryService;
import com.gift.gift.domain.gift.dto.request.GiftPreflightRequest;
import com.gift.gift.domain.gift.dto.response.GiftPreflightResponse;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.gift.support.GiftPolicy;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.ErrorCode;

@Service
public class GiftService {

    private final GiftHistoryRepository giftHistoryRepository;
    private final UserRepository userRepository;
    private final FriendQueryService friendQueryService;
    private final ProductRepository productRepository;

    public GiftService(
            GiftHistoryRepository giftHistoryRepository,
            UserRepository userRepository,
            FriendQueryService friendQueryService,
            ProductRepository productRepository
    ) {
        this.giftHistoryRepository = giftHistoryRepository;
        this.userRepository = userRepository;
        this.friendQueryService = friendQueryService;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public GiftPreflightResponse preflight(Long senderId, GiftPreflightRequest request) {
        if (senderId.equals(request.recipientUserId())) {
            throw new GiftException(ErrorCode.INVALID_REQUEST);
        }

        User recipient = userRepository.findByIdAndStatusAndDeletedAtIsNull(
                        request.recipientUserId(),
                        UserStatus.ACTIVE
                )
                .orElseThrow(() -> new GiftException(ErrorCode.RECIPIENT_NOT_FOUND));

        if (!friendQueryService.areFriends(senderId, recipient.getId())) {
            throw new GiftException(ErrorCode.RECIPIENT_NOT_FRIEND);
        }

        Product product = productRepository.findById(request.productId())
                .filter(foundProduct -> !foundProduct.isDeleted())
                .orElseThrow(() -> new GiftException(ErrorCode.PRODUCT_NOT_FOUND));

        if (product.getQuantity() < request.quantity()) {
            throw new GiftException(ErrorCode.INSUFFICIENT_STOCK);
        }

        BigDecimal totalPrice = product.getPrice().multiply(BigDecimal.valueOf(request.quantity()));
        int maxOrderQuantity = Math.min(product.getQuantity(), GiftPolicy.MAX_QUANTITY);

        return GiftPreflightResponse.from(
                new GiftPreflightResponse.Recipient(recipient.getId(), recipient.getName()),
                new GiftPreflightResponse.Product(
                        product.getId(),
                        product.getPrice(),
                        request.quantity(),
                        totalPrice,
                        maxOrderQuantity
                ),
                null
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
