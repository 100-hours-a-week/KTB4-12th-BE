package com.gift.gift.domain.preference.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.preference.dto.response.SaveDislikeCategoriesResponse;
import com.gift.gift.domain.preference.entity.UserDislikeCategory;
import com.gift.gift.domain.preference.exception.PreferenceException;
import com.gift.gift.domain.preference.repository.UserDislikeCategoryRepository;
import com.gift.gift.domain.preference.support.PreferencePolicy;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.service.CategoryQueryService;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.service.UserQueryService;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class PreferenceSaveService {

    private final UserDislikeCategoryRepository userDislikeCategoryRepository;
    private final CategoryQueryService categoryQueryService;
    private final UserQueryService userQueryService;

    @Transactional
    public SaveDislikeCategoriesResponse saveDislikeCategories(
            Long userId,
            List<Long> categoryIds
    ) {
        validateCount(categoryIds);

        // 선택 이력이 없는 사용자도 동일한 사용자 행 잠금으로 직렬화한다.
        User user = userQueryService.findActiveUserForUpdate(userId)
                .orElseThrow(() -> new PreferenceException(
                        ErrorCode.USER_NOT_FOUND
                ));

        List<Category> requestedCategories = categoryIds.isEmpty()
                ? List.of()
                : categoryQueryService.findCategoriesByIds(categoryIds);

        validateCategories(categoryIds, requestedCategories);

        List<UserDislikeCategory> existingDislikes =
                userDislikeCategoryRepository
                        .findAllByUserIdIncludingDeleted(userId);

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
