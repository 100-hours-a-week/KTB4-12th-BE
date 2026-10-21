package com.gift.gift.domain.product.controller;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.product.dto.response.CategoryListResponse;
import com.gift.gift.domain.product.service.CategoryQueryService;
import com.gift.gift.global.response.ApiResponse;

@RestController
@RequestMapping("/products/categories")
@RequiredArgsConstructor
public class ProductCategoryController {

    private final CategoryQueryService categoryQueryService;

    @GetMapping
    public ApiResponse<CategoryListResponse> getCategories() {
        CategoryListResponse response =
                categoryQueryService.getCategories();

        return ApiResponse.success(
                "상품 카테고리 목록을 조회했습니다.",
                response
        );
    }
}
