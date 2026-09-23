package com.gift.gift.domain.gift.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import com.gift.gift.domain.friend.service.FriendQueryService;
import com.gift.gift.domain.gift.dto.request.GiftCreateRequest;
import com.gift.gift.domain.gift.dto.request.GiftPreflightRequest;
import com.gift.gift.domain.gift.dto.response.GiftPreflightResponse;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.exception.GiftErrorCode;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.gift.support.GiftPolicy;
import com.gift.gift.domain.gift.support.GiftRequestFingerprintGenerator;
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

    private static final String IDEMPOTENCY_KEY_CONSTRAINT_NAME = "uk_gift_histories_sender_idempotency";

    private final GiftHistoryRepository giftHistoryRepository;
    private final UserQueryService userQueryService;
    private final FriendQueryService friendQueryService;
    private final ProductQueryService productQueryService;
    private final PreferenceQueryService preferenceQueryService;
    private final GiftCommandService giftCommandService;
    private final GiftRequestFingerprintGenerator fingerprintGenerator;

    @Transactional(readOnly = true)
    public GiftPreflightResponse preflight(Long senderId, GiftPreflightRequest request) {
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

    // GiftCommandService의 트랜잭션이 멱등 키 UNIQUE 위반으로 전체 롤백된 뒤,
    // 이 트랜잭션 밖에서 기존 결과를 다시 조회해야 하므로 createGift는 트랜잭션을 열지 않는다.
    public GiftHistory createGift(Long senderId, UUID idempotencyKey, GiftCreateRequest request) {
        String requestFingerprint = fingerprintGenerator.generate(request);

        Optional<GiftHistory> existingGift = findExistingGift(senderId, idempotencyKey, requestFingerprint);
        if (existingGift.isPresent()) {
            return existingGift.get();
        }

        try {
            return giftCommandService.createNewGift(senderId, idempotencyKey, requestFingerprint, request);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyKeyUniqueViolation(exception)) {
                throw exception;
            }

            return findExistingGift(senderId, idempotencyKey, requestFingerprint)
                    .orElseThrow(() -> new GiftException(ErrorCode.INTERNAL_SERVER_ERROR));
        }
    }

    private boolean isIdempotencyKeyUniqueViolation(Throwable exception) {
        Throwable current = exception;

        while (current != null) {
            if (current instanceof ConstraintViolationException violation) {
                String constraintName = violation.getConstraintName();

                if (constraintName == null) {
                    return false;
                }

                String normalizedName = constraintName.replace("`", "");
                int separatorIndex = normalizedName.lastIndexOf('.');

                if (separatorIndex >= 0) {
                    normalizedName = normalizedName.substring(separatorIndex + 1);
                }

                return IDEMPOTENCY_KEY_CONSTRAINT_NAME.equalsIgnoreCase(normalizedName)
                        && violation.getSQLException().getErrorCode() == 1062;
            }

            current = current.getCause();
        }

        return false;
    }
}
