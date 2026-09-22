package com.gift.gift.domain.product.cursor;

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
                || payload.version() != 1
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

    private static ProductException invalidCursor() {
        return new ProductException(ErrorCode.INVALID_CURSOR);
    }
}
