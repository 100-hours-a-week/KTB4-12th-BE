package com.gift.gift.domain.user.validation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import com.gift.gift.domain.user.dto.request.SignupTermConsentRequest;

public class UniqueTermConsentIdsValidator
        implements ConstraintValidator<
        UniqueTermConsentIds,
        List<SignupTermConsentRequest>
        > {

    @Override
    public boolean isValid(
            List<SignupTermConsentRequest> value,
            ConstraintValidatorContext context
    ) {
        if (value == null) {
            return true;
        }

        Set<Long> termIds = new HashSet<>();

        for (SignupTermConsentRequest consent : value) {
            if (consent == null || consent.termId() == null) {
                continue;
            }

            if (!termIds.add(consent.termId())) {
                return false;
            }
        }

        return true;
    }
}
