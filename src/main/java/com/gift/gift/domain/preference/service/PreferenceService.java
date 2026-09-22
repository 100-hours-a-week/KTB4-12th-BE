package com.gift.gift.domain.preference.service;

import java.time.LocalDateTime;
import java.util.*;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.preference.dto.response.DislikeCategoryItemResponse;
import com.gift.gift.domain.preference.dto.response.DislikeCategoryListResponse;
import com.gift.gift.domain.preference.dto.response.PreferenceWarningResult;
import com.gift.gift.domain.preference.dto.response.SaveDislikeCategoriesResponse;
import com.gift.gift.domain.preference.entity.UserDislikeCategory;
import com.gift.gift.domain.preference.exception.PreferenceException;
import com.gift.gift.domain.preference.repository.UserDislikeCategoryRepository;
import com.gift.gift.domain.preference.support.PreferencePolicy;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.repository.CategoryRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PreferenceService {

    private final UserDislikeCategoryRepository userDislikeCategoryRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

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

        List<Category> rootCategories = categoryRepository.findAllActiveRootsOrderById();
        List<DislikeCategoryItemResponse> categories = new ArrayList<>();

        for (Category category : rootCategories) {
            boolean isSelected = selectedCategoryIds.contains(category.getId());
            DislikeCategoryItemResponse item = DislikeCategoryItemResponse.from(category, isSelected);
            categories.add(item);
        }

        return DislikeCategoryListResponse.from(categories);
    }

    private void validateUser(Long userId) {
        userRepository.findByIdAndStatusAndDeletedAtIsNull(
                userId,
                UserStatus.ACTIVE
        ).orElseThrow(() -> new PreferenceException(
                ErrorCode.USER_NOT_FOUND
        ));
    }

    @Transactional
    public SaveDislikeCategoriesResponse saveDislikeCategories(
            Long userId,
            List<Long> categoryIds
    ) {
        // 선택 이력이 없는 사용자도 동일한 잠금 대상으로 직렬화한다.
        User user = userRepository.findActiveByIdForUpdate(
                userId,
                UserStatus.ACTIVE
        ).orElseThrow(() -> new PreferenceException(
                ErrorCode.USER_NOT_FOUND
        ));

        validateCount(categoryIds);

        List<Category> requestedCategories = categoryIds.isEmpty()
                ? List.of()
                : categoryRepository.findAllById(categoryIds);

        validateCategories(categoryIds, requestedCategories);

        List<UserDislikeCategory> existingDislikes =
                userDislikeCategoryRepository.findAllByUserIdIncludingDeleted(userId);

        Map<Long, UserDislikeCategory> existingByCategoryId =
                new HashMap<>();

        for (UserDislikeCategory dislike : existingDislikes) {
            existingByCategoryId.put(
                    dislike.getCategory().getId(),
                    dislike
            );
        }

        Set<Long> requestedIds = new HashSet<>(categoryIds);
        LocalDateTime now = LocalDateTime.now();

        for (UserDislikeCategory dislike : existingDislikes) {
            Long categoryId = dislike.getCategory().getId();

            if (!requestedIds.contains(categoryId)) {
                dislike.softDelete(now);
            }
        }

        for (Category category : requestedCategories) {
            UserDislikeCategory existing =
                    existingByCategoryId.get(category.getId());

            if (existing == null) {
                userDislikeCategoryRepository.save(
                        new UserDislikeCategory(user, category)
                );
            } else if (existing.isDeleted()) {
                existing.restore();
            }
        }

        return SaveDislikeCategoriesResponse.from(categoryIds);
    }

    private void validateCount(List<Long> categoryIds) {
        if (categoryIds.size() > PreferencePolicy.MAX_SELECTABLE_COUNT) {
            throw new PreferenceException(
                    ErrorCode.TOO_MANY_DISLIKE_CATEGORIES
            );
        }
    }

    private void validateCategories(
            List<Long> categoryIds,
            List<Category> categories
    ) {
        boolean containsUnavailableCategory =
                categories.size() != categoryIds.size()
                        || categories.stream().anyMatch(category ->
                        !category.isRoot() || category.isDeleted()
                );

        if (containsUnavailableCategory) {
            throw new PreferenceException(
                    ErrorCode.DISLIKE_CATEGORY_NOT_AVAILABLE
            );
        }
    }
}
