package com.gift.gift.domain.preference.validation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class UniqueCategoryIdsValidator
        implements ConstraintValidator<UniqueCategoryIds, List<Long>> {

    @Override
    public boolean isValid(
            List<Long> categoryIds,
            ConstraintValidatorContext context
    ) {
        if (categoryIds == null) {
            return true;
        }

        Set<Long> uniqueIds = new HashSet<>();

        for (Long categoryId : categoryIds) {
            // null 원소는 DTO의 @NotNull에서 처리한다.
            if (categoryId == null) {
                continue;
            }

            if (!uniqueIds.add(categoryId)) {
                return false;
            }
        }

        return true;
    }
}
