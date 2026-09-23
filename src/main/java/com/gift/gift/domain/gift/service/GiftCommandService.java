package com.gift.gift.domain.gift.service;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.friend.service.FriendQueryService;
import com.gift.gift.domain.gift.dto.request.GiftCreateRequest;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.exception.GiftErrorCode;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.service.ProductQueryService;
import com.gift.gift.domain.product.service.ProductStockService;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.service.UserQueryService;
import com.gift.gift.domain.user.support.ActiveUserSummary;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class GiftCommandService {

    private final GiftHistoryRepository giftHistoryRepository;
    private final UserQueryService userQueryService;
    private final FriendQueryService friendQueryService;
    private final ProductQueryService productQueryService;
    private final ProductStockService productStockService;

    @Transactional
    public GiftHistory createNewGift(
            Long senderId,
            UUID idempotencyKey,
            String requestFingerprint,
            GiftCreateRequest request
    ) {
        if (senderId.equals(request.recipientUserId())) {
            throw new GiftException(GiftErrorCode.GIFT_CANNOT_SEND_TO_SELF);
        }

        ActiveUserSummary recipient = userQueryService.findActiveUser(request.recipientUserId())
                .orElseThrow(() -> new GiftException(ErrorCode.RECIPIENT_NOT_FOUND));

        if (!friendQueryService.areFriends(senderId, recipient.userId())) {
            throw new GiftException(ErrorCode.RECIPIENT_NOT_FRIEND);
        }

        Product product = productQueryService.findAvailableProduct(request.productId())
                .orElseThrow(() -> new GiftException(ErrorCode.PRODUCT_NOT_FOUND));

        if (product.getPrice().compareTo(request.expectedUnitPrice()) != 0) {
            throw new GiftException(ErrorCode.GIFT_CONDITIONS_CHANGED);
        }

        boolean stockDeducted = productStockService.deductStockIfAvailable(request.productId(), request.quantity());
        if (!stockDeducted) {
            throw new GiftException(ErrorCode.INSUFFICIENT_STOCK);
        }

        User senderRef = userQueryService.getReference(senderId);
        User recipientRef = userQueryService.getReference(recipient.userId());

        GiftHistory giftHistory = new GiftHistory(
                senderRef,
                recipientRef,
                product,
                request.quantity(),
                product.getPrice(),
                product.getName(),
                idempotencyKey,
                requestFingerprint
        );

        return giftHistoryRepository.save(giftHistory);
    }
}
