package com.gift.gift.domain.product.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.gift.gift.domain.product.dto.response.CategoryListResponse;
import com.gift.gift.domain.product.dto.response.CategoryResponse;
import com.gift.gift.domain.product.dto.response.CategoryResponse.ChildCategoryResponse;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.repository.CategoryRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryQueryService {

    private final CategoryRepository categoryRepository;

    public List<Category> findActiveRootCategories() {
        return categoryRepository.findAllActiveRootsOrderById();
    }

    public List<Category> findCategoriesByIds(
            List<Long> categoryIds
    ) {
        return categoryRepository.findAllById(categoryIds);
    }

    public CategoryListResponse getCategories() {
        List<Category> categories =
                categoryRepository.findAllActiveWithParent();

        // 1. 부모(Root) ID별 자식 카테고리 응답 목록 맵 생성
        Map<Long, List<ChildCategoryResponse>> childrenByParentId = new HashMap<>();

        for (Category category : categories) {
            if (category.isRoot()) {
                continue;
            }

            Category parent = category.getParent();
            if (parent.isDeleted() || !parent.isRoot()) {
                continue;
            }

            List<ChildCategoryResponse> children = childrenByParentId.get(parent.getId());
            if (children == null) {
                children = new ArrayList<>();
                childrenByParentId.put(parent.getId(), children);
            }
            children.add(ChildCategoryResponse.from(category));
        }

        // 2. Root 카테고리를 순회하며 자식 목록을 매핑해 최종 DTO 리스트 조립
        List<CategoryResponse> responses = new ArrayList<>();

        for (Category category : categories) {
            if (!category.isRoot()) {
                continue; // Root 카테고리만 대상으로 조립
            }

            List<ChildCategoryResponse> children = childrenByParentId.get(category.getId());
            if (children == null) {
                children = List.of();
            }

            responses.add(CategoryResponse.from(category, children));
        }

        return CategoryListResponse.from(responses);
    }
}
