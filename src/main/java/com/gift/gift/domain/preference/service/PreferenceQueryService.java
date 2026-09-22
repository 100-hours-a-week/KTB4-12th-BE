package com.gift.gift.domain.preference.service;

import java.util.*;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.preference.dto.response.DislikeCategoryItemResponse;
import com.gift.gift.domain.preference.dto.response.DislikeCategoryListResponse;
import com.gift.gift.domain.preference.dto.response.PreferenceWarningResult;
import com.gift.gift.domain.preference.exception.PreferenceException;
import com.gift.gift.domain.preference.repository.UserDislikeCategoryRepository;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.service.CategoryQueryService;
import com.gift.gift.domain.user.service.UserQueryService;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PreferenceQueryService {

    private final UserDislikeCategoryRepository userDislikeCategoryRepository;
    private final CategoryQueryService categoryQueryService;
    private final UserQueryService userQueryService;

    /*
       상품의 세부 카테고리가 속한 대분류를 수신자가 비선호로 등록했으면 대분류 정보를 반환한다.
       수신자와 상품의 유효성은 호출하는 서비스에서 검증한다.
     */
    public Optional<PreferenceWarningResult> findMatchingWarning(
            Long userId,
            Long productCategoryId
    ) {
        return userDislikeCategoryRepository
                .findActiveByUserIdAndProductCategoryId(userId, productCategoryId)
                .map(dislike -> PreferenceWarningResult.from(dislike.getCategory()));
    }

    public DislikeCategoryListResponse getDislikeCategories(Long userId) {
        validateUser(userId);

        Set<Long> selectedCategoryIds = new HashSet<>(
                userDislikeCategoryRepository.findAllActiveCategoryIdsByUserId(userId)
        );

        List<Category> rootCategories = categoryQueryService.findActiveRootCategories();
        List<DislikeCategoryItemResponse> categories = new ArrayList<>();

        for (Category category : rootCategories) {
            boolean isSelected = selectedCategoryIds.contains(category.getId());
            DislikeCategoryItemResponse item = DislikeCategoryItemResponse.from(category, isSelected);
            categories.add(item);
        }

        return DislikeCategoryListResponse.from(categories);
    }

    private void validateUser(Long userId) {
        userQueryService.findActiveUser(userId)
                .orElseThrow(() -> new PreferenceException(ErrorCode.USER_NOT_FOUND));
    }
}
