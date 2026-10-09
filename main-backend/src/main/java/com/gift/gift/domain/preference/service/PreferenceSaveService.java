package com.gift.gift.domain.preference.service;

import java.time.Clock;
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
import com.gift.gift.domain.recommendation.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.repository.RecipientProfileRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.service.UserQueryService;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class PreferenceSaveService {

    private final UserDislikeCategoryRepository userDislikeCategoryRepository;
    private final CategoryQueryService categoryQueryService;
    private final UserQueryService userQueryService;
    private final RecipientProfileRepository recipientProfileRepository;
    private final Clock clock;

    @Transactional
    public SaveDislikeCategoriesResponse saveDislikeCategories(
            Long userId,
            List<Long> categoryIds
    ) {
        validateCount(categoryIds);

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

        Set<Long> activeBefore = new HashSet<>();

        for (UserDislikeCategory dislike : existingDislikes) {
            Long categoryId = dislike.getCategory().getId();

            existingByCategoryId.put(categoryId, dislike);

            if (!dislike.isDeleted()) {
                activeBefore.add(categoryId);
            }
        }

        Set<Long> requestedIds = new HashSet<>(categoryIds);

        boolean selectionChanged = !activeBefore.equals(requestedIds);
        LocalDateTime now = LocalDateTime.now(clock);

        for (UserDislikeCategory dislike : existingDislikes) {
            Long categoryId = dislike.getCategory().getId();

            if (!requestedIds.contains(categoryId)
                    && !dislike.isDeleted()) {
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

        if (selectionChanged) {
            RecipientProfile profile = recipientProfileRepository
                    .findByRecipient_Id(userId)
                    .orElseGet(() -> recipientProfileRepository.save(
                            new RecipientProfile(user)
                    ));

            profile.recordPreferenceChange(now);
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
