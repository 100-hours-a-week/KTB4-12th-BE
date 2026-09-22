package com.gift.gift.domain.product.query;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import com.gift.gift.domain.product.cursor.ProductCursorCodec;
import com.gift.gift.domain.product.cursor.ProductCursor;
import com.gift.gift.domain.product.repository.ProductSearchCondition;
import com.gift.gift.domain.product.repository.ProductSort;
import com.gift.gift.domain.product.repository.ProductSummaryProjection;

@Component
@RequiredArgsConstructor
public class ProductPageAssembler {

    public static final int PAGE_SIZE = 20;
    public static final int FETCH_COUNT = PAGE_SIZE + 1;

    private final ProductCursorCodec cursorCodec;

    public ProductPage assemble(
            List<ProductSummaryProjection> fetched,
            ProductSearchCondition condition
    ) {
        Objects.requireNonNull(fetched, "상품 조회 결과는 필수입니다.");
        Objects.requireNonNull(condition, "상품 검색 조건은 필수입니다.");

        if (fetched.size() > FETCH_COUNT) {
            throw new IllegalArgumentException(
                    "상품 페이지 조회 결과는 최대 21건이어야 합니다."
            );
        }

        boolean hasNext = fetched.size() > PAGE_SIZE;

        List<ProductSummaryProjection> items = List.copyOf(
                fetched.subList(
                        0,
                        Math.min(fetched.size(), PAGE_SIZE)
                )
        );

        if (!hasNext) {
            return new ProductPage(items, false, null);
        }

        ProductSummaryProjection last = items.getLast();
        boolean newest = condition.sort() == ProductSort.NEWEST;

        ProductCursor payload = new ProductCursor(
                1,
                condition.sort(),
                condition.query(),
                condition.categoryIds(),
                last.productId(),
                last.createdAt(),
                newest ? null : last.views(),
                newest ? null : last.sales()
        );

        String nextCursor = cursorCodec.encode(payload, condition);

        return new ProductPage(items, true, nextCursor);
    }
}
