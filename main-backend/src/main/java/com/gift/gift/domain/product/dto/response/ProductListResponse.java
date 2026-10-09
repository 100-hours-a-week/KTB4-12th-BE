package com.gift.gift.domain.product.dto.response;

import java.util.List;

import com.gift.gift.domain.product.query.ProductPage;
import com.gift.gift.domain.product.repository.ProductSort;
import com.gift.gift.global.pagination.CursorPageResponse.Pagination;

public record ProductListResponse(
        List<ProductSummaryResponse> products,
        ProductSort appliedSort,
        Pagination pagination
) {

    public ProductListResponse {
        products = List.copyOf(products);
    }

    public static ProductListResponse from(
            List<ProductSummaryResponse> products,
            ProductSort appliedSort,
            ProductPage page
    ) {
        return new ProductListResponse(
                products,
                appliedSort,
                new Pagination(
                        page.nextCursor(),
                        page.hasNext()
                )
        );
    }
}
