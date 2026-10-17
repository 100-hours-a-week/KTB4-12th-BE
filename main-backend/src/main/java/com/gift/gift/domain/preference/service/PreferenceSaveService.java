package com.gift.gift.domain.preference.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.entity.CreditTransaction;
import com.gift.gift.domain.credit.entity.CreditTransactionType;
import com.gift.gift.domain.credit.repository.CreditAccountRepository;
import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.preference.dto.response.GiftPreferenceResponse;
import com.gift.gift.domain.preference.dto.response.SaveDislikeCategoriesResponse;
import com.gift.gift.domain.preference.entity.UserDislikeCategory;
import com.gift.gift.domain.preference.entity.UserGiftPreference;
import com.gift.gift.domain.preference.exception.PreferenceException;
import com.gift.gift.domain.preference.repository.UserDislikeCategoryRepository;
import com.gift.gift.domain.preference.repository.UserGiftPreferenceRepository;
import com.gift.gift.domain.preference.support.PreferencePolicy;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.service.CategoryQueryService;
import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.profiling.repository.RecipientProfileRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.service.UserQueryService;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class PreferenceSaveService {

    private static final long UPDATE_COST = 3L;

    private final UserGiftPreferenceRepository userGiftPreferenceRepository;
    private final UserDislikeCategoryRepository userDislikeCategoryRepository;
    private final CategoryQueryService categoryQueryService;
    private final UserQueryService userQueryService;
    private final RecipientProfileRepository recipientProfileRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final Clock clock;

    @Transactional
    public GiftPreferenceResponse saveGiftPreference(Long userId, String preference) {
        User user = userQueryService.findActiveUserForUpdate(userId)
                .orElseThrow(() -> new PreferenceException(ErrorCode.USER_NOT_FOUND));
        String normalized = preference == null ? null : preference.strip();
        if (normalized != null && normalized.isEmpty()) {
            normalized = null;
        }

        UserGiftPreference existing = userGiftPreferenceRepository
                .findByUser_IdAndDeletedAtIsNull(userId).orElse(null);
        boolean changed = false;
        if (existing == null) {
            if (normalized != null) {
                userGiftPreferenceRepository.save(new UserGiftPreference(user, normalized));
                changed = true;
            }
        } else if (!Objects.equals(existing.getPreference(), normalized)) {
            chargePreferenceUpdate(
                    userId,
                    CreditTransactionType.GIFT_PREFERENCE_UPDATE_USE
            );
            existing.changePreference(normalized);
            changed = true;
        }
        if (changed) {
            recordProfileChange(userId, LocalDateTime.now(clock), user);
        }
        return GiftPreferenceResponse.from(normalized);
    }

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

        if (selectionChanged && !user.isFirstLogin()) {
            chargePreferenceUpdate(
                    userId,
                    CreditTransactionType.DISLIKE_CATEGORY_UPDATE_USE
            );
        }

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
            recordProfileChange(userId, now, user);
        }

        return SaveDislikeCategoriesResponse.from(categoryIds);
    }

    private void chargePreferenceUpdate(
            Long userId,
            CreditTransactionType transactionType
    ) {
        CreditAccount account = creditAccountRepository
                .findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "사용자의 크레딧 계좌가 존재하지 않습니다."
                ));

        if (!account.decrease(UPDATE_COST)) {
            throw new PreferenceException(ErrorCode.INSUFFICIENT_CREDIT);
        }

        creditTransactionRepository.save(new CreditTransaction(
                userId,
                transactionType,
                UPDATE_COST,
                account.getBalance(),
                null,
                null,
                null,
                transactionType.name() + ":" + UUID.randomUUID()
        ));
    }

    private void recordProfileChange(
            Long userId,
            LocalDateTime changedAt,
            User user
    ) {
        RecipientProfile profile = recipientProfileRepository
                .findByRecipientIdForUpdate(userId)
                .orElseGet(() -> recipientProfileRepository.save(
                        new RecipientProfile(user)
                ));
        profile.recordPreferenceChange(changedAt);
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
