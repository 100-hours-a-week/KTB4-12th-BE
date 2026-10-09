package com.gift.gift.domain.product.cursor;

import java.util.Objects;

import com.gift.gift.domain.product.exception.ProductException;
import com.gift.gift.domain.product.repository.ProductSearchCondition;
import com.gift.gift.domain.product.repository.ProductSort;
import com.gift.gift.global.exception.ErrorCode;

public final class ProductCursorValidator {

    private ProductCursorValidator() {
    }

    public static void validate(
            ProductCursor payload,
            ProductSearchCondition condition
    ) {
        if (payload == null
                || payload.sort() == null
                || payload.sort() != condition.sort()
                || payload.productId() == null
                || payload.productId() <= 0
                || payload.createdAt() == null) {
            throw invalidCursor();
        }

        if (payload.query() == null
                || payload.categoryIds() == null
                || !payload.query().equals(condition.query())
                || !payload.categoryIds().equals(condition.categoryIds())) {
            throw invalidCursor();
        }

        if (payload.version() == 1) {
            validateLegacyCursor(payload, condition);
        } else if (payload.version() == 2) {
            validateRecipientCursor(payload, condition);
        } else {
            throw invalidCursor();
        }

        if (payload.sort() == ProductSort.NEWEST) {
            if (payload.views() != null || payload.sales() != null) {
                throw invalidCursor();
            }

            return;
        }

        if (payload.views() == null
                || payload.sales() == null
                || payload.views() < 0
                || payload.sales() < 0) {
            throw invalidCursor();
        }
    }

    private static void validateLegacyCursor(
            ProductCursor payload,
            ProductSearchCondition condition
    ) {
        if (condition.recipientUserId() != null
                || condition.isAiRequested()
                || payload.sort() == ProductSort.AI_RECOMMENDED
                || payload.requestedSort() != null
                || payload.recipientUserId() != null
                || payload.analyzedSourceVersion() != null
                || payload.recommendedRegion() != null
                || payload.rankOrder() != null) {
            throw invalidCursor();
        }
    }

    private static void validateRecipientCursor(
            ProductCursor payload,
            ProductSearchCondition condition
    ) {
        if (condition.recipientUserId() == null
                || payload.recipientUserId() == null
                || payload.recipientUserId() <= 0
                || !payload.recipientUserId().equals(condition.recipientUserId())
                || payload.requestedSort() != condition.requestedSort()
                || !Objects.equals(
                payload.analyzedSourceVersion(),
                condition.analyzedSourceVersion()
        )
                || payload.recommendedRegion() == null) {
            throw invalidCursor();
        }

        if (condition.isAiRequested()) {
            if (payload.analyzedSourceVersion() == null
                    || payload.analyzedSourceVersion() < 0) {
                throw invalidCursor();
            }
        } else if (payload.analyzedSourceVersion() != null) {
            throw invalidCursor();
        }

        if (Boolean.TRUE.equals(payload.recommendedRegion())) {
            if (payload.sort() != ProductSort.AI_RECOMMENDED
                    || !condition.isAiRequested()
                    || payload.rankOrder() == null
                    || payload.rankOrder() < 1
                    || payload.rankOrder() > 30) {
                throw invalidCursor();
            }
        } else if (payload.rankOrder() != null) {
            throw invalidCursor();
        }
    }

    private static ProductException invalidCursor() {
        return new ProductException(ErrorCode.INVALID_CURSOR);
    }
}
