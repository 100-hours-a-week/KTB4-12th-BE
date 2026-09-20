package com.gift.gift.domain.product.controller;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import com.gift.gift.domain.product.dto.request.ProductListRequest;
import com.gift.gift.domain.product.dto.response.ProductDetailResponse;
import com.gift.gift.domain.product.dto.response.ProductListResponse;
import com.gift.gift.domain.product.exception.ProductErrorCode;
import com.gift.gift.domain.product.exception.ProductException;
import com.gift.gift.domain.product.service.ProductQueryService;
import com.gift.gift.global.response.ApiResponse;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductQueryService productQueryService;

    @GetMapping
    public ApiResponse<ProductListResponse> getProducts(
            @Valid @ModelAttribute ProductListRequest request,
            BindingResult bindingResult,
            @RequestParam MultiValueMap<String, String> parameters
    ) {
        if (bindingResult.hasErrors()) {
            throw invalidRequest();
        }

        // 수신자 기반 조회는 이번 PR의 지원 범위에서 제외한다.
        // 빈 값으로 전달된 수신자 파라미터도 무시하지 않는다.
        if (parameters.containsKey("recipientUserId")) {
            throw invalidRequest();
        }

        // sort 생략은 허용하지만 sort=처럼 빈 값을 전달하면 거부한다.
        if (parameters.containsKey("sort") && request.sort() == null) {
            throw invalidRequest();
        }

        ProductListResponse response = productQueryService.getProducts(request);

        String message = response.products().isEmpty()
                ? "일치하는 상품이 없습니다."
                : "상품 목록을 조회했습니다.";

        return ApiResponse.success(message, response);
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductDetailResponse> getProductDetail(
            @PathVariable String productId
    ) {
        Long parsedProductId = parseProductId(productId);

        ProductDetailResponse response =
                productQueryService.getProductDetail(parsedProductId);

        return ApiResponse.success(
                "상품 상세를 조회했습니다.",
                response
        );
    }

    private Long parseProductId(String productId) {
        try {
            long parsedProductId = Long.parseLong(productId);

            if (parsedProductId <= 0) {
                throw invalidProductId();
            }

            return parsedProductId;
        } catch (NumberFormatException exception) {
            throw invalidProductId();
        }
    }

    private ProductException invalidProductId() {
        return new ProductException(
                ProductErrorCode.INVALID_PRODUCT_ID
        );
    }

    private ProductException invalidRequest() {
        return new ProductException(ProductErrorCode.INVALID_REQUEST);
    }
}
